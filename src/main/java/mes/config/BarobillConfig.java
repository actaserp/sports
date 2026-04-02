package mes.config;

import com.baroservice.api.BarobillApiProfile;
import com.baroservice.api.BarobillApiService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.MalformedURLException;

@Configuration
public class BarobillConfig {

	@Bean
	public BarobillApiService barobillApiService() {

		try {
			return new BarobillApiService(BarobillApiProfile.RELEASE_SSL);
//			return new BarobillApiService(BarobillApiProfile.TESTBED_SSL);
		} catch (MalformedURLException e) {
			throw new RuntimeException(e);
		}
	}

	}
