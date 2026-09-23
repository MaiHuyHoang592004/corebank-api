package com.corebank.corebank_api.demo.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardEntryController {

	/**
	 * The root used to serve a separate static landing page. It drifted from the dashboard — a
	 * different name, theme and language, a placeholder GitHub link and a note about a hosting
	 * plan no longer in use — while being the first page a visitor sees. The dashboard's own
	 * overview tab now does that job, so the root sends visitors straight to it.
	 */
	@GetMapping({"/", "/dashboard", "/dashboard/"})
	public String index() {
		return "redirect:/dashboard/index.html";
	}
}
