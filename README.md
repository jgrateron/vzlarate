# VzlaRate

Tasas de cambio oficiales del BCV y conversor de divisas en tiempo real.

## Stack

| Tecnología | Versión |
|---|---|
| Java | 21 |
| Spring Boot | 3.3.2 |
| Maven | 3.8+ |
| Thymeleaf | 3.1 |
| HTMX | 2.0 |
| Jsoup | 1.18.1 |
| Caffeine | 3.1 |

## Requisitos

- Java 21
- Maven 3.8+

## Ejecutar

```bash
mvn spring-boot:run
```

La aplicación estará disponible en `http://localhost:8080`.

La variable de entorno `PORT` se puede usar para cambiar el puerto:

```bash
PORT=3000 mvn spring-boot:run
```

## Funcionalidades

- Scraping en tiempo real de las tasas USD y EUR desde [bcv.org.ve](https://www.bcv.org.ve)
- Conversiones: Bs ↔ USD, Bs ↔ EUR, USD ↔ EUR
- Caché de tasas por 30 minutos
- Interfaz responsive con HTMX (sin recarga completa de página)
- Auto-refresh de tasas cada 5 minutos

## Endpoints

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/` | Página principal |
| `GET` | `/fragment/rates` | Fragmento HTMX con las tasas |
| `POST` | `/convert` | Conversión de divisas (retorna fragmento HTML) |

Parámetros de `/convert`:

| Parámetro | Tipo | Valores |
|---|---|---|
| `amount` | `double` | Monto a convertir |
| `from` | `string` | `BS`, `USD`, `EUR` |
| `to` | `string` | `BS`, `USD`, `EUR` |

## Estructura del proyecto

```
src/
├── main/java/com/vzlarate/
│   ├── VzlaRateApplication.java
│   ├── controller/CurrencyController.java
│   ├── model/ExchangeRates.java
│   └── service/BcvScraperService.java
├── main/resources/
│   ├── application.properties
│   ├── static/css/style.css
│   └── templates/
│       ├── index.html
│       └── fragments/
│           ├── rates.html
│           └── result.html
└── test/java/com/vzlarate/
    └── VzlaRateApplicationTests.java
```

## Licencia

MIT
