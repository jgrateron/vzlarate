package com.vzlarate.controller;

import com.vzlarate.model.ExchangeRates;
import com.vzlarate.service.BcvScraperService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.text.NumberFormat;
import java.util.Locale;

@Controller
public class CurrencyController {

    private static final Logger log = LoggerFactory.getLogger(CurrencyController.class);
    private final BcvScraperService scraperService;

    public CurrencyController(BcvScraperService scraperService) {
        this.scraperService = scraperService;
    }

    @GetMapping("/")
    public String index(Model model) {
        try {
            ExchangeRates rates = scraperService.fetchRates();
            model.addAttribute("rates", rates);
            model.addAttribute("error", null);
        } catch (Exception e) {
            log.error("Error fetching rates for index page", e);
            model.addAttribute("rates", null);
            model.addAttribute("error", "No se pudieron cargar las tasas del BCV. Intente nuevamente.");
        }
        return "index";
    }

    /**
     * HTMX endpoint to refresh only the rates cards and date.
     */
    @GetMapping("/fragment/rates")
    public String refreshRates(Model model) {
        try {
            // Spring Cache with Caffeine caches the result; to force a refresh we'd need
            // @CacheEvict, but for simplicity we rely on cache expiration (30 min by default).
            ExchangeRates rates = scraperService.fetchRates();
            model.addAttribute("rates", rates);
            model.addAttribute("error", null);
        } catch (Exception e) {
            log.error("Error refreshing rates", e);
            model.addAttribute("rates", null);
            model.addAttribute("error", "Error al actualizar. Intente de nuevo.");
        }
        return "fragments/rates";
    }

    /**
     * HTMX endpoint for currency conversion.
     * Converts the amount to all three currencies and returns an HTML fragment with the results.
     */
    @PostMapping("/convert")
    public String convert(
            @RequestParam("amount") @NotNull @Min(0) Double amount,
            @RequestParam("from") @NotBlank String from,
            Model model) {

        try {
            ExchangeRates rates = scraperService.fetchRates();
            double amountInBs = toBs(amount, from, rates);

            model.addAttribute("amount", amount);
            model.addAttribute("sourceCurrency", from);
            model.addAttribute("bsFormatted", formatCurrency(amountInBs, "BS"));
            model.addAttribute("usdFormatted", formatCurrency(amountInBs / rates.usdRate(), "USD"));
            model.addAttribute("eurFormatted", formatCurrency(amountInBs / rates.eurRate(), "EUR"));
            model.addAttribute("error", null);

        } catch (Exception e) {
            log.error("Error during conversion", e);
            model.addAttribute("error", "Error al realizar la conversión. Intente de nuevo.");
        }

        return "fragments/result";
    }

    /**
     * Convert any supported currency to bolívares.
     * BCV rates are expressed as Bs per foreign currency unit (e.g., 755.90 Bs = 1 USD).
     */
    private double toBs(double amount, String from, ExchangeRates rates) {
        return switch (from) {
            case "USD" -> amount * rates.usdRate();
            case "EUR" -> amount * rates.eurRate();
            case "BS" -> amount;
            default -> throw new IllegalArgumentException("Moneda origen no soportada: " + from);
        };
    }

    /**
     * Format a numeric value for display according to currency conventions.
     */
    private String formatCurrency(double value, String currency) {
        return switch (currency) {
            case "BS" -> {
                NumberFormat nf = NumberFormat.getInstance(Locale.GERMANY);
                nf.setMinimumFractionDigits(2);
                nf.setMaximumFractionDigits(2);
                yield "Bs. " + nf.format(value);
            }
            case "USD" -> {
                NumberFormat nf = NumberFormat.getInstance(Locale.US);
                nf.setMinimumFractionDigits(2);
                nf.setMaximumFractionDigits(4);
                yield "$ " + nf.format(value);
            }
            case "EUR" -> {
                NumberFormat nf = NumberFormat.getInstance(Locale.GERMANY);
                nf.setMinimumFractionDigits(2);
                nf.setMaximumFractionDigits(4);
                yield "€ " + nf.format(value);
            }
            default -> String.format("%.2f", value);
        };
    }
}
