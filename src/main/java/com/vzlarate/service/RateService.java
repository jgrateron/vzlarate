package com.vzlarate.service;

import com.vzlarate.model.ExchangeRates;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Sirve las tasas del BCV con semántica stale-while-revalidate:
 * - snapshot fresca  -> devuelve al instante, sin scrapear
 * - snapshot vieja   -> devuelve las tasas anteriores al instante y refresca una vez en background
 * - sin snapshot     -> bloquea sincrónicamente en el primer fetch (cold start), single-flight
 * - fallo del refresco -> conserva la snapshot anterior, loguea warning, nunca falla el request
 */
@Service
public class RateService {

    private static final Logger log = LoggerFactory.getLogger(RateService.class);

    /** Cuánto se considera fresca una snapshot. */
    Duration ttl = Duration.ofMinutes(30);
    /** Mínimo tiempo entre intentos de scrape (BCV caído -> no martillar el sitio). */
    Duration minAttemptInterval = Duration.ofSeconds(60);

    private final BcvScraperService scraperService;

    // Hilo único daemon: el single-flight garantiza a lo sumo 1 tarea encolada
    // y nunca queremos dos scrapes de 15s concurrentes.
    private final ExecutorService refreshExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "bcv-refresh");
                t.setDaemon(true);
                return t;
            });

    private volatile Snapshot snapshot;     // null hasta el primer fetch exitoso
    private volatile boolean refreshing;    // single-flight: un refresco a la vez
    private volatile Instant lastAttemptAt; // backoff: mínimo entre intentos

    public RateService(BcvScraperService scraperService) {
        this.scraperService = scraperService;
    }

    public ExchangeRates getRates() {
        Snapshot s = snapshot;
        if (s != null) {
            if (s.isFresh(ttl)) {
                return s.rates();                       // fresco -> inmediato, sin scrape
            }
            refreshInBackground();                      // fire-and-forget
            return s.rates().withFromCache(true);       // viejo -> inmediato, refresca en background
        }
        return coldStart();
    }

    /**
     * Primer fetch (arranque en frío): bloquea el hilo del request para que
     * la página nunca se renderice vacía. Los concurrentes esperan en el monitor
     * y reutilizan el mismo fetch.
     */
    private synchronized ExchangeRates coldStart() {
        Snapshot s = snapshot;
        if (s != null) return s.rates();                // otro hilo ganó la carrera mientras esperábamos
        if (!minIntervalElapsed(lastAttemptAt)) {
            // Un intento falló hace poco: fallar rápido en vez de bloquear 15s por visitante
            throw new IllegalStateException("No se pudo obtener las tasas del BCV. Intente en unos minutos.");
        }
        lastAttemptAt = Instant.now();
        try {
            ExchangeRates rates = scraperService.fetchRates(); // bloquea hasta bcv.scraper.timeout
            snapshot = new Snapshot(rates, Instant.now());
            log.info("Cold start: rates loaded");
            return rates;
        } catch (RuntimeException e) {
            log.warn("Cold start fetch failed: {}", e.getMessage());
            throw e;                                     // el controller muestra el error en español
        }
    }

    /** Programa un refresco en background; a lo sumo uno en vuelo y uno por minAttemptInterval. */
    private void refreshInBackground() {
        if (refreshing) return;                          // lectura volátil barata
        synchronized (this) {
            if (refreshing) return;                      // single-flight
            if (!minIntervalElapsed(lastAttemptAt)) return; // backoff
            refreshing = true;
            lastAttemptAt = Instant.now();
        }
        try {
            refreshExecutor.execute(this::doRefresh);
        } catch (RejectedExecutionException e) {         // app apagándose
            refreshing = false;
        }
    }

    private void doRefresh() {
        try {
            ExchangeRates rates = scraperService.fetchRates(); // Jsoup 15s, en el hilo background
            snapshot = new Snapshot(rates, Instant.now());
            log.info("Background refresh succeeded: USD {}, EUR {}", rates.usdRate(), rates.eurRate());
        } catch (RuntimeException e) {
            // Se conserva la snapshot vieja; la página sigue mostrando la última tasa conocida
            log.warn("Background refresh failed, keeping previous rates: {}", e.getMessage());
        } finally {
            refreshing = false;
        }
    }

    private boolean minIntervalElapsed(Instant last) {
        return last == null
                || Duration.between(last, Instant.now()).compareTo(minAttemptInterval) >= 0;
    }

    @PreDestroy
    void shutdown() {
        refreshExecutor.shutdownNow();
    }

    private record Snapshot(ExchangeRates rates, Instant fetchedAt) {
        boolean isFresh(Duration ttl) {
            return Duration.between(fetchedAt, Instant.now()).compareTo(ttl) < 0;
        }
    }
}
