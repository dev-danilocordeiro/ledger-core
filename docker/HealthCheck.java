import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal HTTP healthcheck for a distroless runtime image, which has no shell,
 * curl, or wget. Uses only java.net.http, part of the standard JDK since 11,
 * so it needs no dependency beyond the JRE already present in the image.
 * Exit code 0 on HTTP 200 from the actuator health endpoint, 1 otherwise —
 * matching what Docker's HEALTHCHECK/compose healthcheck expects.
 */
public class HealthCheck {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8080/actuator/health"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        System.exit(response.statusCode() == 200 ? 0 : 1);
    }
}
