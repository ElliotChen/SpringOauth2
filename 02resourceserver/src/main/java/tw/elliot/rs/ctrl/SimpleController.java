package tw.elliot.rs.ctrl;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SimpleController {
	@GetMapping("/whoami")
	public String whoami(@AuthenticationPrincipal(expression = "username") String name) {
		return name;
	}

	@GetMapping("/whoareu")
	public String whoareu(Authentication auth) {
		return auth.getName();
	}
}
