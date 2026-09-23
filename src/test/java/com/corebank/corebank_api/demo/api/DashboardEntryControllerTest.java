package com.corebank.corebank_api.demo.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The site root and both dashboard spellings land on the dashboard. */
@Tag("fast")
class DashboardEntryControllerTest {

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DashboardEntryController()).build();

	@ParameterizedTest
	@ValueSource(strings = {"/", "/dashboard", "/dashboard/"})
	@DisplayName("entry paths redirect to the dashboard")
	void redirectsToDashboard(String path) throws Exception {
		mockMvc.perform(get(path))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/dashboard/index.html"));
	}
}
