package com.vzlarate.service;

import com.vzlarate.model.ExchangeRates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Sin contexto Spring ni red: un fake manual de BcvScraperService que sobreescribe
 * fetchRates() con resultado configurable, flag de fallo y una "compuerta"
 * (CompletableFuture) para bloquear el scrape a voluntad.
 * ttl y minAttemptInterval se acortan a milisegundos para envejecer snapshots al instante.
 */
class RateServiceTest {

    static class FakeScraper extends BcvScraperService {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean fail;
        final AtomicReference<CompletableFuture<ExchangeRates>> gate =
                new AtomicReference<>(CompletableFuture.completedFuture(
                        new ExchangeRates(755.9, 872.8, LocalDateTime.now())));

        @Override
        public ExchangeRates fetchRates() {
            calls.incrementAndGet();
            if (fail) {
                throw new BcvScrapingException("scrape fail", null);
            }
            return gate.get().join(); // bloquea hasta que el test complete el future
        }
    }

    private static final ExchangeRates NEW_RATES = new ExchangeRates(800.0, 900.0, LocalDateTime.now());

    FakeScraper scraper;
    RateService service;

    @BeforeEach
    void setUp() {
        scraper = new FakeScraper();
        service = new RateService(scraper);
    }

    /** Siembra una snapshot fresca vía cold start (camino público, sin seams extra). */
    private ExchangeRates seedFresh() {
        return service.getRates();
    }

    /** Envejece la snapshot: ttl de 1ms y backoff de 1ms para no esperar 30 min reales. */
    private void makeStale() {
        service.ttl = Duration.ofMillis(1);
        service.minAttemptInterval = Duration.ofMillis(1);
        awaitCallCount(1); // el cold start del seed ya corrió
        sleep(10);         // la snapshot ya tiene >1ms: quedó vieja
    }

    private void awaitCallCount(int n) {
        long deadline = System.currentTimeMillis() + 3000;
        while (scraper.calls.get() < n && System.currentTimeMillis() < deadline) {
            sleep(10);
        }
        assertThat(scraper.calls).hasValueGreaterThanOrEqualTo(n);
    }

    /** Espera (con polls) a que getRates() devuelva las tasas nuevas servidas frescas. */
    private void awaitFreshRates(ExchangeRates expected) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            ExchangeRates r = service.getRates();
            if (r.usdRate() == expected.usdRate() && r.eurRate() == expected.eurRate() && !r.fromCache()) {
                return;
            }
            sleep(10);
        }
        fail("Las tasas nuevas no se publicaron en background");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @Test
    void freshReadReturnsRatesWithoutScraping() {
        ExchangeRates fresh = seedFresh();

        ExchangeRates r = service.getRates();

        assertThat(r.usdRate()).isEqualTo(fresh.usdRate());
        assertThat(r.eurRate()).isEqualTo(fresh.eurRate());
        assertThat(r.fromCache()).isFalse();
        assertThat(scraper.calls).hasValue(1); // ninguna visita nueva al BCV
    }

    @Test
    void staleReadReturnsOldRatesImmediatelyAndRefreshesOnceInBackground() {
        ExchangeRates old = seedFresh();
        makeStale();
        scraper.gate.set(new CompletableFuture<>()); // el refresco background queda bloqueado dentro del fake

        long t0 = System.nanoTime();
        ExchangeRates r = service.getRates();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(r.usdRate()).isEqualTo(old.usdRate());
        assertThat(r.eurRate()).isEqualTo(old.eurRate());
        assertThat(r.fromCache()).isTrue();    // sirve la última tasa conocida
        assertThat(elapsedMs).isLessThan(100); // no bloquea esperando al BCV

        awaitCallCount(2); // el refresco background arrancó
        sleep(50);
        assertThat(scraper.calls).hasValue(2); // y solo uno: sin estampida

        // Al completar el refresco, la próxima lectura ve las tasas nuevas
        service.ttl = Duration.ofMinutes(30);  // restaura la ventana de frescura
        scraper.gate.get().complete(NEW_RATES);
        awaitFreshRates(NEW_RATES);

        assertThat(scraper.calls).hasValue(2); // nunca volvió a tocar el BCV
    }

    @Test
    void concurrentStaleReadsTriggerExactlyOneBackgroundFetch() {
        ExchangeRates old = seedFresh();
        makeStale();
        scraper.gate.set(new CompletableFuture<>()); // refresco bloqueado: la estampida no puede avanzar

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ExchangeRates>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return service.getRates();
            }));
        }
        start.countDown();

        for (Future<ExchangeRates> f : results) {
            try {
                assertThat(f.get(2, TimeUnit.SECONDS).usdRate()).isEqualTo(old.usdRate());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        pool.shutdownNow();

        awaitCallCount(2); // cold start del seed + UNA refresco background
        sleep(50);
        assertThat(scraper.calls).hasValue(2); // sin estampida
        scraper.gate.get().complete(NEW_RATES); // cierra la compuerta para el hilo daemon
    }

    @Test
    void coldStartBlocksUntilFetchCompletesAndConcurrentCallersShareIt() {
        scraper.gate.set(new CompletableFuture<>()); // primer fetch bloqueado

        CompletableFuture<ExchangeRates> a = CompletableFuture.supplyAsync(service::getRates);
        awaitCallCount(1); // A está dentro de fetchRates
        sleep(50);
        assertThat(a).isNotDone(); // A sigue bloqueado esperando al BCV

        CompletableFuture<ExchangeRates> b = CompletableFuture.supplyAsync(service::getRates);
        sleep(50);
        assertThat(b).isNotDone(); // B espera el monitor, no hace otro fetch

        scraper.gate.get().complete(NEW_RATES);
        assertThat(a.join().usdRate()).isEqualTo(NEW_RATES.usdRate());
        assertThat(b.join().usdRate()).isEqualTo(NEW_RATES.usdRate());
        assertThat(scraper.calls).hasValue(1); // un solo fetch compartido
    }

    @Test
    void refreshFailureKeepsOldRatesAndDoesNotThrow() {
        ExchangeRates old = seedFresh();
        makeStale();
        scraper.fail = true;

        ExchangeRates r = service.getRates(); // no lanza: el fallo ocurre en el hilo background

        assertThat(r.usdRate()).isEqualTo(old.usdRate());
        assertThat(r.fromCache()).isTrue();
        awaitCallCount(2);
        sleep(50); // deja terminar el doRefresh fallido

        service.minAttemptInterval = Duration.ofSeconds(60); // backoff: que no reintente
        ExchangeRates r2 = service.getRates();
        assertThat(r2.usdRate()).isEqualTo(old.usdRate()); // la snapshot no cambió
        assertThat(r2.fromCache()).isTrue();
        assertThat(scraper.calls).hasValue(2);
    }

    @Test
    void coldStartFailureThrowsAndRetriesAreThrottled() {
        scraper.fail = true;

        assertThatThrownBy(service::getRates).isInstanceOf(BcvScraperService.BcvScrapingException.class);

        // Reintento inmediato: falla rápido sin volver a tocar el BCV (backoff de 60s)
        assertThatThrownBy(service::getRates).isInstanceOf(IllegalStateException.class);
        assertThat(scraper.calls).hasValue(1);

        // Pasado el intervalo, se recupera
        scraper.fail = false;
        service.minAttemptInterval = Duration.ofMillis(1);
        sleep(10);
        assertThat(service.getRates().usdRate()).isEqualTo(755.9);
        assertThat(scraper.calls).hasValue(2);
    }

    @Test
    void staleReadsAreThrottledAfterARefreshAttempt() {
        seedFresh();
        makeStale();

        service.getRates(); // dispara el refresco (backoff de 1ms en makeStale)
        awaitCallCount(2);
        sleep(50); // el refresco termina publicando tasas nuevas...

        service.minAttemptInterval = Duration.ofSeconds(60); // ...y ahora el backoff aplica
        ExchangeRates r = service.getRates(); // la snapshot quedó vieja de nuevo (ttl=1ms)
        assertThat(r.fromCache()).isTrue();
        sleep(200);
        assertThat(scraper.calls).hasValue(2); // sin segundo intento dentro del intervalo

        service.minAttemptInterval = Duration.ofMillis(1);
        sleep(10);
        service.getRates();
        awaitCallCount(3); // pasado el intervalo, sí reintenta
    }
}
