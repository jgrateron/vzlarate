package com.vzlarate.service;

import com.vzlarate.model.ExchangeRates;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.text.NumberFormat;
import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
public class BcvScraperService {

    private static final Logger log = LoggerFactory.getLogger(BcvScraperService.class);
    private static final String BCV_URL = "https://www.bcv.org.ve/";

    @Value("${bcv.scraper.timeout:15000}")
    private int timeout;

    @Value("${bcv.scraper.user-agent:Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36}")
    private String userAgent;

    public ExchangeRates fetchRates() {
        log.info("Fetching exchange rates from BCV...");
        try {
            Document doc = Jsoup.connect(BCV_URL)
                    .userAgent(userAgent)
                    .timeout(timeout)
                    .sslSocketFactory(createTrustAllSslSocketFactory())
                    .followRedirects(true)
                    .get();

            // Extract USD rate from: div#dolar strong.strong-tb
            double usdRate = extractRateById(doc, "dolar");

            // Extract EUR rate from: div#euro strong.strong-tb
            double eurRate = extractRateById(doc, "euro");

            if (usdRate <= 0 || eurRate <= 0) {
                throw new RuntimeException(
                        "Could not extract valid exchange rates. USD=" + usdRate + ", EUR=" + eurRate);
            }

            // Extract fecha valor from: span.date-display-single
            LocalDateTime lastUpdated = extractDate(doc);

            log.info("Rates fetched — USD: {}, EUR: {}, Date: {}", usdRate, eurRate, lastUpdated);
            return new ExchangeRates(usdRate, eurRate, lastUpdated);

        } catch (Exception e) {
            log.error("Failed to scrape BCV rates: {}", e.getMessage());
            throw new BcvScrapingException("No se pudo obtener las tasas del BCV. Intente de nuevo.", e);
        }
    }

    /**
     * Extract rate from the BCV page structure:
     * <div id="{currencyId}">
     *   <div class="field-content">
     *     <div class="row recuadrotsmc">
     *       <div class="col-sm-6 col-xs-6 centrado textp">
     *         <strong class="strong-tb"> RATE_VALUE</strong>
     *       </div>
     *     </div>
     *   </div>
     * </div>
     */
    private double extractRateById(Document doc, String currencyId) {
        Element container = doc.getElementById(currencyId);
        if (container == null) {
            log.warn("Element #{} not found on BCV page", currencyId);
            return 0;
        }

        Element strong = container.selectFirst("strong.strong-tb");
        if (strong == null) {
            log.warn("strong.strong-tb not found inside #{}", currencyId);
            return 0;
        }

        String rawText = strong.text().trim();
        return parseVenezuelanNumber(rawText);
    }

    /**
     * Extract the date from:
     * <span class="date-display-single" content="2026-08-06T00:00:00-04:00">Jueves, 06 Agosto 2026</span>
     */
    private LocalDateTime extractDate(Document doc) {
        Element dateSpan = doc.selectFirst("span.date-display-single");
        if (dateSpan != null) {
            String content = dateSpan.attr("content");
            if (!content.isEmpty()) {
                try {
                    return LocalDateTime.parse(content, DateTimeFormatter.ISO_DATE_TIME);
                } catch (Exception e) {
                    log.warn("Could not parse date from content attribute: {}", content);
                }
            }
        }

        // Fallback: try to parse the text content
        if (dateSpan != null) {
            String text = dateSpan.text().trim();
            log.info("Date text fallback: {}", text);
        }

        return LocalDateTime.now();
    }

    /**
     * Parse Venezuelan number format where comma is the decimal separator.
     * E.g., "755,90010000" → 755.90010000
     * E.g., " 872,83784547" → 872.83784547
     */
    public double parseVenezuelanNumber(String rawText) {
        if (rawText == null || rawText.isEmpty()) return 0;

        // Trim whitespace
        String number = rawText.trim();

        try {
            // Use German locale where comma is decimal separator
            NumberFormat nf = NumberFormat.getInstance(Locale.GERMANY);
            return nf.parse(number).doubleValue();
        } catch (ParseException e) {
            log.warn("Could not parse number with German locale: '{}'", number);
            // Fallback: manually replace comma with dot
            try {
                return Double.parseDouble(number.replace(",", "."));
            } catch (NumberFormatException ex) {
                log.error("Failed to parse number: '{}'", number);
                return 0;
            }
        }
    }

    /**
     * Creates an SSL socket factory that trusts all certificates.
     * Required because the BCV SSL certificate may not be in the JVM trust store.
     */
    private static SSLSocketFactory createTrustAllSslSocketFactory() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SSL socket factory", e);
        }
    }

    public static class BcvScrapingException extends RuntimeException {
        public BcvScrapingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
