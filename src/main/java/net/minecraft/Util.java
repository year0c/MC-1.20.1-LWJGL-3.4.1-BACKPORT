package net.minecraft;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.MoreExecutors;
import com.mojang.datafixers.DataFixUtils;
import com.mojang.datafixers.Typed;
import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DSL.TypeReference;
import com.mojang.datafixers.types.Type;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceImmutableList;
import it.unimi.dsi.fastutil.objects.ReferenceList;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.Objects;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.TimeSupplier;
import net.minecraft.util.crash.CrashException;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.crash.CrashReportSection;
import net.minecraft.util.logging.UncaughtExceptionHandler;
import net.minecraft.datafixer.Schemas;
import net.minecraft.util.math.MathHelper;         
import net.minecraft.util.math.random.Random;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class Util {
    static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEFAULT_MAX_THREADS = 255;
    private static final int DEFAULT_SAFE_FILE_OPERATION_RETRIES = 10;
    private static final String MAX_THREADS_SYSTEM_PROPERTY = "max.bg.threads";
    private static final ExecutorService BACKGROUND_EXECUTOR = makeExecutor("Main");
    private static final ExecutorService IO_POOL = makeIoExecutor("IO-Worker-", false);
    private static final ExecutorService DOWNLOAD_POOL = makeIoExecutor("Download-", true);
    private static final DateTimeFormatter FILENAME_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss", Locale.ROOT);

    public static final int LINEAR_LOOKUP_THRESHOLD = 8;
    public static final long NANOS_PER_MILLI = 1_000_000L;
    public static TimeSupplier.Nanoseconds nanoTimeSupplier = System::nanoTime;

    @FunctionalInterface
        public interface Ticker {
            long read();
    }   

    public static final Ticker TICKER = () -> Util.nanoTimeSupplier.getAsLong();   
    public static final UUID NIL_UUID = new UUID(0L, 0L);
    public static final FileSystemProvider ZIP_FILE_SYSTEM_PROVIDER =
            FileSystemProvider.installedProviders().stream()
                    .filter(provider -> provider.getScheme().equalsIgnoreCase("jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No jar file system provider found"));

    private static Consumer<String> thePauser = string -> {};

    public static <K, V> Collector<Entry<? extends K, ? extends V>, ?, Map<K, V>> toMap() {
        return Collectors.toMap(Entry::getKey, Entry::getValue);
    }

    public static <T> Collector<T, ?, List<T>> toMutableList() {
        return Collectors.toCollection(Lists::newArrayList);
    }

    @FunctionalInterface
    public interface Property<T extends Comparable<T>> {
        String getName(T value);
    }

    @SuppressWarnings("unchecked")
    public static <T extends Comparable<T>> String getPropertyName(Property<T> property, Object value) {
        return property.getName((T) value);
    }

    public static String makeDescriptionId(String type, @Nullable Identifier id) {
        return id == null
            ? type + ".unregistered_sadface"
            : type + "." + id.getNamespace() + "." + id.getPath().replace('/', '.');
    }

    public static long getMillis() {
        return getNanos() / NANOS_PER_MILLI;
    }

    public static long getNanos() {
        return nanoTimeSupplier.getAsLong();
    }   

    public static long getEpochMillis() {
        return Instant.now().toEpochMilli();
    }

    public static String getFilenameFormattedDateTime() {
        return FILENAME_DATE_TIME_FORMATTER.format(ZonedDateTime.now());
    }

    private static ExecutorService makeExecutor(String name) {
        int threads = MathHelper.clamp(Runtime.getRuntime().availableProcessors() - 1, 1, getMaxThreads());
        if (threads <= 0) {
            return MoreExecutors.newDirectExecutorService();
        }

        AtomicInteger index = new AtomicInteger(1);
        return new ForkJoinPool(threads, pool -> {
            ForkJoinWorkerThread worker = new ForkJoinWorkerThread(pool) {
                @Override
                protected void onTermination(Throwable throwable) {
                    if (throwable != null) {
                        LOGGER.warn("{} died", getName(), throwable);
                    } else {
                        LOGGER.debug("{} shutdown", getName());
                    }
                    super.onTermination(throwable);
                }
            };
            worker.setName("Worker-" + name + "-" + index.getAndIncrement());
            return worker;
        }, Util::onThreadException, true);
    }

    private static int getMaxThreads() {
        String value = System.getProperty(MAX_THREADS_SYSTEM_PROPERTY);
        if (value != null) {
            try {
                int threads = Integer.parseInt(value);
                if (threads >= 1 && threads <= DEFAULT_MAX_THREADS) {
                    return threads;
                }
                LOGGER.error("Wrong {} property value '{}'. Should be an integer value between 1 and {}.",
                        MAX_THREADS_SYSTEM_PROPERTY, value, DEFAULT_MAX_THREADS);
            } catch (NumberFormatException exception) {
                LOGGER.error("Could not parse {} property value '{}'. Should be an integer value between 1 and {}.",
                        MAX_THREADS_SYSTEM_PROPERTY, value, DEFAULT_MAX_THREADS);
            }
        }
        return DEFAULT_MAX_THREADS;
    }

    public static ExecutorService backgroundExecutor() {
        return BACKGROUND_EXECUTOR;
    }

    public static ExecutorService ioPool() {
        return IO_POOL;
    }

    public static ExecutorService nonCriticalIoPool() {
        return DOWNLOAD_POOL;
    }

    public static void shutdownExecutors() {
        shutdownExecutor(BACKGROUND_EXECUTOR);
        shutdownExecutor(IO_POOL);
    }

    private static void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        boolean terminated;
        try {
            terminated = executor.awaitTermination(3L, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            terminated = false;
        }
        if (!terminated) {
            executor.shutdownNow();
        }
    }

    private static ExecutorService makeIoExecutor(String prefix, boolean daemon) {
        AtomicInteger index = new AtomicInteger(1);
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(prefix + index.getAndIncrement());
            thread.setDaemon(daemon);
            thread.setUncaughtExceptionHandler(Util::onThreadException);
            return thread;
        });
    }

    public static void throwAsRuntime(Throwable throwable) {
        throw throwable instanceof RuntimeException runtime
                ? runtime
                : new RuntimeException(throwable);
    }

    private static void onThreadException(Thread thread, Throwable throwable) {
        pauseInIde(throwable);
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            throwable = throwable.getCause();
        }

        if (throwable instanceof CrashException reported) {
            Bootstrap.println(reported.getReport().asString());
            System.exit(-1);
        }
        LOGGER.error(String.format(Locale.ROOT, "Caught exception in thread %s", thread), throwable);
    }

    @Nullable
    public static Type<?> fetchChoiceType(DSL.TypeReference typeReference, String name) {
        return !SharedConstants.useChoiceTypeRegistrations
            ? null
            : doFetchChoiceType(typeReference, name);
    }   

    public static boolean IS_RUNNING_IN_IDE =
    System.getProperty("idea.launcher.port") != null
    || System.getProperty("spring.boot.run.mainClass") != null;

    @Nullable
    private static Type<?> doFetchChoiceType(TypeReference typeReference, String name) {
        Type<?> type = null;
        try { type = Schemas.getFixer()
            .getSchema(DataFixUtils.makeKey(
                SharedConstants.getGameVersion().getSaveVersion().getId()))
            .getChoiceType(typeReference, name);
        }
        catch (IllegalArgumentException exception) {
            LOGGER.error("No data fixer registered for {}", name);
                if (IS_RUNNING_IN_IDE) {
                        throw exception;
                }
        }
        return type;
    }

    public static Runnable wrapThreadWithTaskName(String name, Runnable runnable) {
        if (!IS_RUNNING_IN_IDE) {
            return runnable;
        }
        return () -> {
            Thread thread = Thread.currentThread();
            String oldName = thread.getName();
            thread.setName(name);
            try {
                runnable.run();
            } finally {
                thread.setName(oldName);
            }
        };
    }

    public static <V> Supplier<V> wrapThreadWithTaskName(String name, Supplier<V> supplier) {
        if (!IS_RUNNING_IN_IDE) {
            return supplier;
        }
        return () -> {
            Thread thread = Thread.currentThread();
            String oldName = thread.getName();
            thread.setName(name);
            try {
                return supplier.get();
            } finally {
                thread.setName(oldName);
            }
        };
    }

    public static <T> String getRegisteredName(Registry<T> registry, T value) {
        Identifier id = registry.getId(value);
            return id == null ? "[unregistered]" : id.toString();
    }   

    public static <T> Predicate<T> allOf(List<? extends Predicate<T>> predicates) {
        List<? extends Predicate<T>> copy = List.copyOf(predicates);
        return switch (copy.size()) {
            case 0 -> value -> true;
            case 1 -> copy.get(0);
            case 2 -> copy.get(0).and(copy.get(1));
            default -> value -> {
                for (Predicate<T> predicate : copy) {
                    if (!predicate.test(value)) {
                        return false;
                    }
                }
                return true;
            };
        };
    }

    public static <T> Predicate<T> anyOf(List<? extends Predicate<T>> predicates) {
        List<? extends Predicate<T>> copy = List.copyOf(predicates);
        return switch (copy.size()) {
            case 0 -> value -> false;
            case 1 -> copy.get(0);
            case 2 -> copy.get(0).or(copy.get(1));
            default -> value -> {
                for (Predicate<T> predicate : copy) {
                    if (predicate.test(value)) {
                        return true;
                    }
                }
                return false;
            };
        };
    }

    public static OS getPlatform() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return OS.WINDOWS;
        if (os.contains("mac")) return OS.OSX;
        if (os.contains("solaris") || os.contains("sunos")) return OS.SOLARIS;
        if (os.contains("linux")) return OS.LINUX;
        return os.contains("unix") ? OS.LINUX : OS.UNKNOWN;
    }

    public static Stream<String> getVmArguments() {
        RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
        return bean.getInputArguments().stream().filter(argument -> argument.startsWith("-X"));
    }

    public static <T> T lastOf(List<T> list) {
        return list.get(list.size() - 1);
    }

    public static <T> T findNextInIterable(Iterable<T> iterable, @Nullable T value) {
        Iterator<T> iterator = iterable.iterator();
        T first = iterator.next();
        if (value != null) {
            T current = first;
            while (current != value) {
                if (iterator.hasNext()) {
                    current = iterator.next();
                }
            }
            if (iterator.hasNext()) {
                return iterator.next();
            }
        }
        return first;
    }

    public static <T> T findPreviousInIterable(Iterable<T> iterable, @Nullable T value) {
        Iterator<T> iterator = iterable.iterator();
        T previous = null;
        while (iterator.hasNext()) {
            T current = iterator.next();
            if (current == value) {
                if (previous == null) {
                    previous = iterator.hasNext() ? Iterators.getLast(iterator) : value;
                }
                break;
            }
            previous = current;
        }
        return previous;
    }

    public static <T> T make(Supplier<T> supplier) {
        return supplier.get();
    }

    public static <T> T make(T value, Consumer<? super T> consumer) {
        consumer.accept(value);
        return value;
    }

    public static <V> CompletableFuture<List<V>> sequence(
            List<? extends CompletableFuture<V>> futures) {
        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        if (futures.size() == 1) {
            return futures.get(0).thenApply(List::of);
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }

    public static <V> CompletableFuture<List<V>> sequenceFailFast(
            List<? extends CompletableFuture<? extends V>> futures) {
        CompletableFuture<List<V>> failed = new CompletableFuture<>();
        return fallibleSequence(futures, failed::completeExceptionally)
                .applyToEither(failed, Function.identity());
    }

    public static <V> CompletableFuture<List<V>> sequenceFailFastAndCancel(
            List<? extends CompletableFuture<? extends V>> futures) {
        CompletableFuture<List<V>> failed = new CompletableFuture<>();
        return fallibleSequence(futures, throwable -> {
            if (failed.completeExceptionally(throwable)) {
                futures.forEach(future -> future.cancel(true));
            }
        }).applyToEither(failed, Function.identity());
    }

    private static <V> CompletableFuture<List<V>> fallibleSequence(
            List<? extends CompletableFuture<? extends V>> futures,
            Consumer<Throwable> onFailure) {
        List<V> results = Lists.newArrayListWithCapacity(futures.size());
        CompletableFuture<?>[] wrapped = new CompletableFuture<?>[futures.size()];
        for (int index = 0; index < futures.size(); index++) {
            results.add(null);
            int resultIndex = index;
            wrapped[index] = futures.get(index).whenComplete((value, throwable) -> {
                if (throwable != null) {
                    onFailure.accept(throwable);
                } else {
                    results.set(resultIndex, value);
                }
            });
        }
        return CompletableFuture.allOf(wrapped).thenApply(ignored -> results);
    }

    public static <T> Optional<T> ifElse(
            Optional<T> optional, Consumer<T> present, Runnable absent) {
        if (optional.isPresent()) {
            present.accept(optional.get());
        } else {
            absent.run();
        }
        return optional;
    }

    public static <T> Supplier<T> name(Supplier<T> supplier, Supplier<String> name) {
        return supplier;
    }

    public static Runnable name(Runnable runnable, Supplier<String> name) {
        return runnable;
    }

    public static void logAndPauseIfInIde(String message) {
        LOGGER.error(message);
        if (IS_RUNNING_IN_IDE) doPause(message);
    }

    public static void logAndPauseIfInIde(String message, Throwable throwable) {
        LOGGER.error(message, throwable);
        if (IS_RUNNING_IN_IDE) doPause(message);
    }

    public static <T extends Throwable> T pauseInIde(T throwable) {
        if (IS_RUNNING_IN_IDE) {
            LOGGER.error("Trying to throw a fatal exception, pausing in IDE", throwable);
            doPause(throwable.getMessage());
        }
        return throwable;
    }

    public static void setPause(Consumer<String> pauser) {
        thePauser = pauser;
    }

    private static void doPause(String message) {
        Instant start = Instant.now();
        LOGGER.warn("Did you remember to set a breakpoint here?");
        if (Duration.between(start, Instant.now()).toMillis() <= 500L) {
            thePauser.accept(message);
        }
    }

    public static String describeError(Throwable throwable) {
        if (throwable.getCause() != null) {
            return describeError(throwable.getCause());
        }
        return throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
    }

    public static <T> T getRandom(T[] values, Random random) {
        return values[random.nextInt(values.length)];
    }

    public static int getRandom(int[] values, Random random) {
        return values[random.nextInt(values.length)];
    }

    public static <T> T getRandom(List<T> values, Random random) {
        return values.get(random.nextInt(values.size()));
    }

    public static <T> Optional<T> getRandomSafe(List<T> values, Random random) {
        return values.isEmpty() ? Optional.empty() : Optional.of(getRandom(values, random));
    }

    private static BooleanSupplier createRenamer(Path from, Path to) {
        return new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                try {
                    Files.move(from, to);
                    return true;
                } catch (IOException exception) {
                    LOGGER.error("Failed to rename", exception);
                    return false;
                }
            }

            @Override
            public String toString() {
                return "rename " + from + " to " + to;
            }
        };
    }

    private static BooleanSupplier createDeleter(Path path) {
        return new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                try {
                    Files.deleteIfExists(path);
                    return true;
                } catch (IOException exception) {
                    LOGGER.warn("Failed to delete", exception);
                    return false;
                }
            }

            @Override
            public String toString() {
                return "delete old " + path;
            }
        };
    }

    private static BooleanSupplier createFileDeletedCheck(Path path) {
        return new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return !Files.exists(path);
            }

            @Override
            public String toString() {
                return "verify that " + path + " is deleted";
            }
        };
    }

    private static BooleanSupplier createFileCreatedCheck(Path path) {
        return new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return Files.isRegularFile(path);
            }

            @Override
            public String toString() {
                return "verify that " + path + " is present";
            }
        };
    }

    private static boolean executeInSequence(BooleanSupplier... operations) {
        for (BooleanSupplier operation : operations) {
            if (!operation.getAsBoolean()) {
                LOGGER.warn("Failed to execute {}", operation);
                return false;
            }
        }
        return true;
    }

    private static boolean runWithRetries(int retries, String description,
                                          BooleanSupplier... operations) {
        for (int attempt = 0; attempt < retries; attempt++) {
            if (executeInSequence(operations)) {
                return true;
            }
            LOGGER.error("Failed to {}, retrying {}/{}", description, attempt, retries);
        }
        LOGGER.error("Failed to {}, aborting, progress might be lost", description);
        return false;
    }

    public static void safeReplaceFile(Path path, Path replacement, Path backup) {
        safeReplaceOrMoveFile(path, replacement, backup, false);
    }

    public static boolean safeReplaceOrMoveFile(
            Path path, Path replacement, Path backup, boolean allowFailure) {
        if (Files.exists(path)
                && !runWithRetries(DEFAULT_SAFE_FILE_OPERATION_RETRIES,
                        "create backup " + backup,
                        createDeleter(backup), createRenamer(path, backup),
                        createFileCreatedCheck(backup))) {
            return false;
        }
        if (!runWithRetries(DEFAULT_SAFE_FILE_OPERATION_RETRIES,
                "remove old " + path, createDeleter(path), createFileDeletedCheck(path))) {
            return false;
        }
        if (!runWithRetries(DEFAULT_SAFE_FILE_OPERATION_RETRIES,
                "replace " + path + " with " + replacement,
                createRenamer(replacement, path), createFileCreatedCheck(path)) && !allowFailure) {
            runWithRetries(DEFAULT_SAFE_FILE_OPERATION_RETRIES,
                    "restore " + path + " from " + backup,
                    createRenamer(backup, path), createFileCreatedCheck(path));
            return false;
        }
        return true;
    }

    public static int offsetByCodepoints(String value, int index, int offset) {
        int length = value.length();
        if (offset >= 0) {
            for (int moved = 0; index < length && moved < offset; moved++) {
                if (Character.isHighSurrogate(value.charAt(index++))
                        && index < length && Character.isLowSurrogate(value.charAt(index))) {
                    index++;
                }
            }
        } else {
            for (int moved = offset; index > 0 && moved < 0; moved++) {
                index--;
                if (Character.isLowSurrogate(value.charAt(index))
                        && index > 0 && Character.isHighSurrogate(value.charAt(index - 1))) {
                    index--;
                }
            }
        }
        return index;
    }

    public static Consumer<String> prefix(String prefix, Consumer<String> consumer) {
        return value -> consumer.accept(prefix + value);
    }

    public static DataResult<int[]> fixedSize(IntStream stream, int size) {
        int[] values = stream.limit((long) size + 1L).toArray();
        if (values.length == size) return DataResult.success(values);

        Supplier<String> error = () -> "Input is not a list of " + size + " ints";
        return values.length >= size
                ? DataResult.error(error, Arrays.copyOf(values, size))
                : DataResult.error(error);
    }

    public static DataResult<long[]> fixedSize(LongStream stream, int size) {
        long[] values = stream.limit((long) size + 1L).toArray();
        if (values.length == size) return DataResult.success(values);

        Supplier<String> error = () -> "Input is not a list of " + size + " longs";
        return values.length >= size
                ? DataResult.error(error, Arrays.copyOf(values, size))
                : DataResult.error(error);
    }

    public static <T> DataResult<List<T>> fixedSize(List<T> values, int size) {
        if (values.size() == size) return DataResult.success(values);

        Supplier<String> error = () -> "Input is not a list of " + size + " elements";
        return values.size() >= size
                ? DataResult.error(error, values.subList(0, size))
                : DataResult.error(error);
    }

    public static void startTimerHackThread() {
        Thread thread = new Thread("Timer hack thread") {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(2_147_483_647L);
                    } catch (InterruptedException exception) {
                        LOGGER.warn("Timer hack thread interrupted, that really should not happen");
                        return;
                    }
                }
            }
        };
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler(new UncaughtExceptionHandler(LOGGER));
        thread.start();
    }

    public static void copyBetweenDirs(Path from, Path to, Path source) throws IOException {
        Path relative = from.relativize(source);
        Files.copy(source, to.resolve(relative));
    }

    @FunctionalInterface
        public interface CharPredicate {
            boolean test(char c);
    }

    public static String sanitizeName(String value, CharPredicate predicate) {
        return value.toLowerCase(Locale.ROOT).chars()
                .mapToObj(character -> predicate.test((char) character)
                        ? Character.toString((char) character)
                        : "_")
                .collect(Collectors.joining());
    }

    public static class SingleKeyCache<K, V> {
        private final Function<K, V> function;
        private K lastKey;
        private V lastValue;

        public SingleKeyCache(Function<K, V> function) {
                this.function = function;
        }

        public V get(K key) {
            if (!Objects.equals(key, this.lastKey)) {
                this.lastValue = this.function.apply(key);
                this.lastKey = key;
            }
            return this.lastValue;
        }
    }    

    public static <K, V> SingleKeyCache<K, V> singleKeyCache(Function<K, V> function) {
        return new SingleKeyCache<>(function);
    }

    public static <T, R> Function<T, R> memoize(Function<T, R> function) {
        return new Function<>() {
            private final Map<T, R> cache = new ConcurrentHashMap<>();

            @Override
            public R apply(T value) {
                return cache.computeIfAbsent(value, function);
            }

            @Override
            public String toString() {
                return "memoize/1[function=" + function + ", size=" + cache.size() + "]";
            }
        };
    }

    public static <T, U, R> BiFunction<T, U, R> memoize(BiFunction<T, U, R> function) {
        return new BiFunction<>() {
            private final Map<Pair<T, U>, R> cache = new ConcurrentHashMap<>();

            @Override
            public R apply(T first, U second) {
                return cache.computeIfAbsent(Pair.of(first, second),
                        pair -> function.apply(pair.getFirst(), pair.getSecond()));
            }

            @Override
            public String toString() {
                return "memoize/2[function=" + function + ", size=" + cache.size() + "]";
            }
        };
    }

    public static <T> List<T> toShuffledList(Stream<T> stream, Random random) {
        ObjectArrayList<T> values = (ObjectArrayList<T>) stream.collect(ObjectArrayList.toList());
        shuffle(values, random);
        return values;
    }

    public static IntArrayList toShuffledList(IntStream stream, Random random) {
        IntArrayList values = IntArrayList.wrap(stream.toArray());
        for (int index = values.size(); index > 1; index--) {
            int swap = random.nextInt(index);
            values.set(index - 1, values.set(swap, values.getInt(index - 1)));
        }
        return values;
    }

    public static <T> List<T> shuffledCopy(T[] values, Random random) {
        ObjectArrayList<T> copy = new ObjectArrayList<>(values);
        shuffle(copy, random);
        return copy;
    }

    public static <T> List<T> shuffledCopy(ObjectArrayList<T> values, Random random) {
        ObjectArrayList<T> copy = new ObjectArrayList<>(values);
        shuffle(copy, random);
        return copy;
    }

    public static <T> void shuffle(List<T> values, Random random) {
        for (int index = values.size(); index > 1; index--) {
            int swap = random.nextInt(index);
            values.set(index - 1, values.set(swap, values.get(index - 1)));
        }
    }

    public static <T> CompletableFuture<T> blockUntilDone(
            Function<Executor, CompletableFuture<T>> function) {
        return blockUntilDone(function, CompletableFuture::isDone);
    }

    public static <T> T blockUntilDone(Function<Executor, T> function, Predicate<T> done) {
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
        T result = function.apply(queue::add);
        while (!done.test(result)) {
            try {
                Runnable task = queue.poll(100L, TimeUnit.MILLISECONDS);
                if (task != null) task.run();
            } catch (InterruptedException exception) {
                LOGGER.warn("Interrupted wait");
                break;
            }
        }
        if (!queue.isEmpty()) {
            LOGGER.warn("Tasks left in queue: {}", queue.size());
        }
        return result;
    }

    public static <T> ToIntFunction<T> createIndexLookup(List<T> values) {
        int size = values.size();
        if (size < LINEAR_LOOKUP_THRESHOLD) return values::indexOf;

        Object2IntMap<T> indices = new Object2IntOpenHashMap<>(size);
        indices.defaultReturnValue(-1);
        for (int index = 0; index < size; index++) {
            indices.put(values.get(index), index);
        }
        return indices;
    }

    public static <T> ToIntFunction<T> createIndexIdentityLookup(List<T> values) {
        int size = values.size();
        if (size < LINEAR_LOOKUP_THRESHOLD) {
            ReferenceList<T> referenceList = new ReferenceImmutableList<>(values);
            return referenceList::indexOf;
        }

        Reference2IntMap<T> indices = new Reference2IntOpenHashMap<>(size);
        indices.defaultReturnValue(-1);
        for (int index = 0; index < size; index++) {
            indices.put(values.get(index), index);
        }
        return indices;
    }

    public static <A, B> Typed<B> writeAndReadTypedOrThrow(
            Typed<A> typed, Type<B> type, UnaryOperator<Dynamic<?>> operator) {
        Dynamic<?> dynamic = typed.write().result().orElseThrow(() -> new IllegalStateException("Failed to write dynamic"));     
        return readTypedOrThrow(type, operator.apply(dynamic), true);
    }

    public static <T> Typed<T> readTypedOrThrow(Type<T> type, Dynamic<?> dynamic) {
        return readTypedOrThrow(type, dynamic, false);
    }

    public static <T> Typed<T> readTypedOrThrow(
            Type<T> type, Dynamic<?> dynamic, boolean allowPartial) {
        DataResult<Typed<T>> result = type.readTyped(dynamic).map(Pair::getFirst);
        try {
            return allowPartial
                    ? result.resultOrPartial(msg -> {}).orElseThrow(IllegalStateException::new)
                    : result.result().orElseThrow(IllegalStateException::new);
        } catch (IllegalStateException exception) {
            CrashReport report = CrashReport.create(exception, "Reading type");
            CrashReportSection section = report.addElement("Info");
            section.add("Data", dynamic);
            section.add("Type", type);
            throw new CrashException(report);   
        }
    }

    public static <T> List<T> copyAndAdd(List<T> values, T value) {
        return ImmutableList.<T>builderWithExpectedSize(values.size() + 1)
                .addAll(values).add(value).build();
    }

    public static <T> List<T> copyAndAdd(T value, List<T> values) {
        return ImmutableList.<T>builderWithExpectedSize(values.size() + 1)
                .add(value).addAll(values).build();
    }

    public static <K, V> Map<K, V> copyAndPut(Map<K, V> values, K key, V value) {
        return ImmutableMap.<K, V>builderWithExpectedSize(values.size() + 1)
                .putAll(values).put(key, value).buildKeepingLast();
    }

    public enum OS {
        LINUX("linux"),
        SOLARIS("solaris"),
        WINDOWS("windows") {
            @Override
            protected String[] getOpenUrlArguments(URL url) {
                return new String[]{"rundll32", "url.dll,FileProtocolHandler", url.toString()};
            }
        },
        OSX("mac") {
            @Override
            protected String[] getOpenUrlArguments(URL url) {
                return new String[]{"open", url.toString()};
            }
        },
        UNKNOWN("unknown");

        private final String telemetryName;

        OS(String telemetryName) {
            this.telemetryName = telemetryName;
        }

        public void openUrl(URL url) {
            try {
                Process process = Runtime.getRuntime().exec(getOpenUrlArguments(url));
                process.getInputStream().close();
                process.getErrorStream().close();
                process.getOutputStream().close();
            } catch (IOException exception) {
                LOGGER.error("Couldn't open url '{}'", url, exception);
            }
        }   

        public void openUri(URI uri) {
            try {
                openUrl(uri.toURL());
            } catch (MalformedURLException exception) {
                LOGGER.error("Couldn't open uri '{}'", uri, exception);
            }
        }

        public void openFile(File file) {
            try {
                openUrl(file.toURI().toURL());
            } catch (MalformedURLException exception) {
                LOGGER.error("Couldn't open file '{}'", file, exception);
            }
        }

        protected String[] getOpenUrlArguments(URL url) {
            String value = url.toString();
            if ("file".equals(url.getProtocol())) {
                value = value.replace("file:", "file://");
            }
            return new String[]{"xdg-open", value};
        }

        public void openUri(String value) {
            try {
                openUrl(new URI(value).toURL());
            } catch (URISyntaxException | MalformedURLException | IllegalArgumentException exception) {
                LOGGER.error("Couldn't open uri '{}'", value, exception);
            }
        }

        public String telemetryName() {
            return telemetryName;
        }
    }
}