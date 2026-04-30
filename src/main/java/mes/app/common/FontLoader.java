package mes.app.common;


import com.openhtmltopdf.extend.FSSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class FontLoader {

	@Value("${app.font.path}")
	private String fontPath;

	@Autowired
	private ResourceLoader resourceLoader;

	private final Map<String, byte[]> fontCache = new HashMap<>();

	@PostConstruct
	public void init() throws IOException {
		loadFont("hangeulMin4",   "HangeuljaeMin4-Regular.ttf");
		loadFont("nanumMyeongjo", "NanumMyeongjo-Bold.ttf");
		loadFont("malgun",        "malgun.ttf");
		loadFont("malgunbd",      "malgunbd.ttf");
		loadFont("notoSans",      "NotoSansKR-Regular.ttf");
		loadFont("notoSansBold",  "NotoSansKR-Bold.ttf");
	}

	private void loadFont(String key, String fileName) throws IOException {
		Resource resource = resourceLoader.getResource(fontPath + fileName);
		if (!resource.exists()) throw new IOException("폰트 없음: " + fontPath + fileName);
		try (InputStream is = resource.getInputStream()) {
			fontCache.put(key, is.readAllBytes());
//			log.info("폰트 로딩 성공 [{}]", key);
		}
	}

	public FSSupplier<InputStream> get(String key) {
		byte[] bytes = fontCache.get(key);
		if (bytes == null) throw new IllegalArgumentException("폰트 키 없음: " + key);
		return () -> new ByteArrayInputStream(bytes);
	}
}
