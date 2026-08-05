package com.vzlarate;

import com.vzlarate.service.BcvScraperService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class VzlaRateApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    void parseVenezuelanNumber() {
        BcvScraperService service = new BcvScraperService();

        // These would need @Value injection; test the parser directly
        // Rate values from BCV: comma is decimal separator
        assertThat(service.parseVenezuelanNumber("755,90010000")).isEqualTo(755.90010000);
        assertThat(service.parseVenezuelanNumber("872,83784547")).isEqualTo(872.83784547);
        assertThat(service.parseVenezuelanNumber(" 755,90010000")).isEqualTo(755.90010000);
        assertThat(service.parseVenezuelanNumber("35,25")).isEqualTo(35.25);
        assertThat(service.parseVenezuelanNumber("0,00")).isZero();
    }
}
