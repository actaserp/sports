package mes.app.files;

import mes.domain.entity.SystemOption;
import mes.domain.repository.SystemOptionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import javax.servlet.http.HttpSession;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/logo")
public class LogoController {

    @Lazy
    @Autowired
    private S3Client ncpS3Client;

    @Value("${ncp.object-storage.bucket}")
    private String ncpBucket;

    @Autowired
    private SystemOptionRepository systemOptionRepository;

    @GetMapping
    public ResponseEntity<byte[]> getLogo(
            @RequestParam("type") String type,
            HttpSession session) {

        String dbKey = (String) session.getAttribute("db_key");

        if (dbKey == null) {
            return fallback(type);
        }

        SystemOption opt = systemOptionRepository.getByCodeAndSpjangcd(type, dbKey);
        if (opt == null || opt.getValue() == null) {
            return fallback(type);
        }

        String objectKey = opt.getValue().replaceAll("^/+", "");

        try {
            ResponseBytes<GetObjectResponse> obj = ncpS3Client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(ncpBucket)
                            .key(objectKey)
                            .build());

            String contentType = obj.response().contentType();
            MediaType mediaType = contentType != null
                    ? MediaType.parseMediaType(contentType)
                    : MediaType.IMAGE_PNG;

            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS))
                    .body(obj.asByteArray());

        } catch (Exception e) {
            return fallback(type);
        }
    }

    private ResponseEntity<byte[]> fallback(String type) {
        String resourcePath = type.equals("LOGO_A")
                ? "static/images/logo/actas_log_a.png"
                : "static/images/logo/actas_log.png";
        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            byte[] bytes = StreamUtils.copyToByteArray(resource.getInputStream());
            MediaType mediaType = type.equals("LOGO_A") ? MediaType.valueOf("image/png") : MediaType.IMAGE_PNG;
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS))
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
