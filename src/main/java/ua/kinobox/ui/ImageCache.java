package ua.kinobox.ui;

import javafx.scene.image.Image;
import ua.kinobox.data.UserFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Завантаження зображень з кешем у пам'яті та на диску (~/.kinobox/cache/images).
 * Мережа — не більше {@link #PARALLEL_DOWNLOADS} завантажень одночасно; декодування — у фоні.
 */
public final class ImageCache {

    private static final Path DIR = UserFiles.resolve("cache", "images");
    /** Скільки зображень завантажуємо одночасно. Решта чекає в черзі. */
    private static final int PARALLEL_DOWNLOADS = 6;
    /** Скільки разів пробуємо, якщо сервер не відповів вчасно. */
    private static final int ATTEMPTS = 2;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * {@link #PARALLEL_DOWNLOADS} потоків — стільки ж завантажень одночасно: кожен потік качає одну картинку за раз.
     * daemon — щоб ці потоки не заважали програмі завершитися.
     */
    private static final ExecutorService DOWNLOADS = Executors.newFixedThreadPool(PARALLEL_DOWNLOADS,
            Thread.ofPlatform().daemon().name("image-download-", 1).factory());

    /** URL → файл на диску. Один файл завантажується лише раз, навіть якщо його просять одночасно. */
    private static final Map<String, CompletableFuture<Path>> FILES = new ConcurrentHashMap<>();
    /** URL + розмір → готове зображення в пам'яті. */
    private static final Map<String, CompletableFuture<Image>> IMAGES = new ConcurrentHashMap<>();

    private ImageCache() {
    }

    /**
     * Зображення, зменшене до {@code width}×{@code height} (зі збереженням пропорцій).
     * Ніколи не завершується помилкою: якщо щось пішло не так, результат — null.
     */
    public static CompletableFuture<Image> get(String url, double width, double height) {
        if (!isHttps(url)) {
            // Адреси приходять і з JSON-файлів користувача: локальні файли й незашифровані адреси не відкриваємо
            System.err.println("Зображення пропущено: дозволені лише https-адреси");
            return CompletableFuture.completedFuture(null);
        }
        String key = url + "@" + (int) width + "x" + (int) height;
        CompletableFuture<Image> future = IMAGES.computeIfAbsent(key, k -> file(url)
                // Async: навіть якщо файл уже на диску, декодуємо НЕ в потоці, що викликав get()
                .thenApplyAsync(path -> decode(path, width, height))
                .exceptionally(error -> {
                    System.err.println("Зображення " + url + ": " + error.getMessage());
                    return null;
                }));
        // Невдачу не запам'ятовуємо: наступний виклик get() спробує ще раз
        future.thenAccept(img -> {
            if (img == null) {
                IMAGES.remove(key, future);
            }
        });
        return future;
    }

    /** Розмір кешу на диску в байтах. Рахується у фоні — файлів можуть бути сотні. */
    public static CompletableFuture<Long> diskSize() {
        return CompletableFuture.supplyAsync(() -> {
            if (!Files.isDirectory(DIR)) {
                return 0L;
            }
            try (Stream<Path> files = Files.list(DIR)) {
                return files.mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (IOException e) {
                        return 0; // файл щойно видалили — просто не рахуємо
                    }
                }).sum();
            } catch (IOException e) {
                return 0L;
            }
        });
    }

    /**
     * Видаляє файли кешу. Зображення, які вже на екрані, лишаються в пам'яті;
     * усе інше завантажиться знову, коли знадобиться.
     */
    public static CompletableFuture<Void> clearDisk() {
        FILES.clear();
        IMAGES.clear();
        return CompletableFuture.runAsync(() -> {
            if (!Files.isDirectory(DIR)) {
                return;
            }
            try (Stream<Path> files = Files.list(DIR)) {
                files.forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        // .part-файл, який саме завантажується, може бути зайнятий — пропускаємо
                        System.err.println("Не вдалося видалити " + path + ": " + e.getMessage());
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static CompletableFuture<Path> file(String url) {
        CompletableFuture<Path> future = FILES.computeIfAbsent(url, u -> {
            Path target = DIR.resolve(fileName(u));
            if (Files.exists(target)) {
                return CompletableFuture.completedFuture(target);   // з диска — без черги
            }
            return CompletableFuture.supplyAsync(() -> download(u, target), DOWNLOADS);
        });
        future.whenComplete((path, error) -> {
            if (error != null) {
                FILES.remove(url, future);
            }
        });
        return future;
    }

    /** Виконується в одному з потоків DOWNLOADS, тому тут можна чекати синхронно. */
    private static Path download(String url, Path target) {
        // Пишемо в тимчасовий .part і лише потім перейменовуємо:
        // якщо програму закриють посеред завантаження, у кеші не лишиться обрізаного файлу
        Path part = DIR.resolve(target.getFileName() + ".part");
        var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        try {
            Files.createDirectories(DIR);
            for (int attempt = 1; ; attempt++) {
                try {
                    var response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(part));
                    if (response.statusCode() != 200) {
                        Files.deleteIfExists(part);
                        throw new IOException("HTTP " + response.statusCode());
                    }
                    return Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (HttpTimeoutException e) {
                    if (attempt >= ATTEMPTS) {
                        throw e;
                    }
                    // інакше — ще одна спроба
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Завантаження перервано", e);
        }
    }

    private static Image decode(Path path, double width, double height) {
        // backgroundLoading = false: ми вже у фоновому потоці, тож читаємо синхронно
        var image = new Image(path.toUri().toString(), width, height, true, true, false);
        if (image.isError()) {
            throw new IllegalStateException("не вдалося прочитати " + path, image.getException());
        }
        return image;
    }

    /** Ім'я файлу в кеші — SHA-256 від URL: коротке, унікальне, без заборонених у Windows символів. */
    private static String fileName(String url) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash) + ".jpg";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 є в кожній Java, сюди не потрапимо
        }
    }

    private static boolean isHttps(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
