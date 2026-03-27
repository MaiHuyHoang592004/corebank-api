package com.corebank.corebank_api.demo.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardEntryController {

	@GetMapping({"/dashboard", "/dashboard/"})
	public String index() {
		return "redirect:/dashboard/index.html";
	}
}
