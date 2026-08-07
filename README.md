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

## Docker

### Construir y ejecutar local

```bash
docker build -t vzlarate .
docker run -p 8080:8080 -e PORT=8080 vzlarate
```

### Imagen en Docker Hub

```bash
docker pull jgrateron/vzlarate:latest
docker run -p 8080:8080 -e PORT=8080 jgrateron/vzlarate:latest
```

### Publicar una nueva versión

```bash
docker build -t jgrateron/vzlarate:1.0.3 .
docker tag jgrateron/vzlarate:1.0.3 jgrateron/vzlarate:latest
docker push jgrateron/vzlarate:1.0.3
docker push jgrateron/vzlarate:latest
```

La imagen usa multi-stage build: compila con Maven + JDK 21 Alpine y ejecuta solo con JRE 21 Alpine.

## Funcionalidades

- Scraping en tiempo real de las tasas USD y EUR desde [bcv.org.ve](https://www.bcv.org.ve)
- Conversor con selección de moneda de origen (Bs, USD o EUR) que muestra el equivalente en las tres divisas a la vez
- Refresco en background (stale-while-revalidate): las tasas se cachean 30 minutos y, al expirar, la página devuelve al instante la última tasa conocida mientras un hilo en background busca las nuevas
- Interfaz responsive con HTMX (sin recarga completa de página)
- Auto-refresh de tasas cada 5 minutos

## Endpoints

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/` | Página principal |
| `GET` | `/fragment/rates` | Fragmento HTMX con las tasas |
| `POST` | `/convert` | Conversión a las tres divisas (retorna fragmento HTML) |

Parámetros de `/convert`:

| Parámetro | Tipo | Valores |
|---|---|---|
| `amount` | `double` | Monto a convertir |
| `from` | `string` | `BS`, `USD`, `EUR` |

## Estructura del proyecto

```
src/
├── main/java/com/vzlarate/
│   ├── VzlaRateApplication.java
│   ├── controller/CurrencyController.java
│   ├── model/ExchangeRates.java
│   └── service/
│       ├── BcvScraperService.java
│       └── RateService.java
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
