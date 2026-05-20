package tw.elliot.pkceresourceclient.web;

import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tw.elliot.pkceresourceclient.service.ResourceServerClient;

@Controller
public class HomeController {

    private final ResourceServerClient resourceServerClient;

    public HomeController(ResourceServerClient resourceServerClient) {
        this.resourceServerClient = resourceServerClient;
    }

    @GetMapping("/")
    public String home(@AuthenticationPrincipal OidcUser user, Model model) {
        model.addAttribute("name", user != null ? user.getSubject() : "anonymous");
        return "index";
    }

    @GetMapping("/me")
    public String me(Model model) {
        Map<String, Object> body = resourceServerClient.me();
        model.addAttribute("body", body);
        return "me";
    }

    @GetMapping("/update-parameter")
    public String updateParameterForm() {
        return "update-parameter";
    }

    @PostMapping("/update-parameter")
    public String updateParameterSubmit(@RequestParam("paramType") String paramType,
                                        @RequestParam(value = "version", defaultValue = "1.0") String version,
                                        Model model) {
        Map<String, Object> result = resourceServerClient.updateParameter(paramType, version);
        model.addAttribute("result", result);
        return "update-parameter";
    }
}
