package com.corebank.corebank_api.observability;

import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a correlation id on every request, in the MDC and on the response.
 *
 * <p>The application already threads a {@code correlationId} through its money commands, but it
 * arrives in the request body, so it only exists once a command has been parsed and it never
 * reaches a log line written before or after that. This filter establishes one for the whole
 * request, which is what makes a log search by correlation id return the full picture — the
 * rejected request and the 500 as well as the successful posting.
 *
 * <p>The id is echoed back in the response header so that a caller reporting a problem can quote
 * the exact value to search for, and it is accepted from the request so a caller that already has
 * one (a gateway, another service) keeps a single id across the hop.
 *
 * <p>Trace and span ids are handled separately by Micrometer Tracing. This one is deliberately
 * distinct: a trace id identifies a technical request path, while a correlation id can be pinned to
 * a business operation and survives being written into audit and outbox rows.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter extends OncePerRequestFilter {

	static final String HEADER = "X-Correlation-Id";
	static final String MDC_KEY = "correlationId";

	/**
	 * An inbound id ends up in log files, so it is treated as untrusted input: anything that is not
	 * a plain identifier of sane length is replaced rather than propagated. Without this a caller
	 * could inject newlines and forge log entries.
	 */
	private static final Pattern ACCEPTABLE = Pattern.compile("^[A-Za-z0-9_.:-]{1,128}$");

	/**
	 * Optional on purpose. The filter must keep working if tracing is switched off, in which case
	 * there is no {@code Tracer} bean and the id is written straight to the MDC.
	 */
	private final ObjectProvider<Tracer> tracer;

	public CorrelationIdFilter(ObjectProvider<Tracer> tracer) {
		this.tracer = tracer;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		String correlationId = resolve(request.getHeader(HEADER));
		response.setHeader(HEADER, correlationId);

		// Publish through tracing baggage rather than straight to the MDC.
		//
		// correlationId is declared in management.tracing.baggage.correlation.fields, which makes
		// Micrometer Tracing the owner of that MDC key: it rewrites the key when a span scope opens
		// and would blank out a value the filter had put there itself. Going through baggage also
		// means the id rides the trace context across a thread hop or a service boundary, which a
		// bare MDC write never would.
		Tracer activeTracer = tracer.getIfAvailable();
		if (activeTracer == null) {
			withMdcOnly(correlationId, request, response, filterChain);
			return;
		}

		try (BaggageInScope scope = activeTracer.createBaggageInScope(MDC_KEY, correlationId)) {
			filterChain.doFilter(request, response);
		}
	}

	private void withMdcOnly(
			String correlationId,
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain)
			throws ServletException, IOException {

		MDC.put(MDC_KEY, correlationId);
		try {
			filterChain.doFilter(request, response);
		} finally {
			// Servlet threads are pooled. Leaving the value behind would stamp the next, unrelated
			// request with this one's id.
			MDC.remove(MDC_KEY);
		}
	}

	private static String resolve(String candidate) {
		if (candidate != null && ACCEPTABLE.matcher(candidate).matches()) {
			return candidate;
		}
		return UUID.randomUUID().toString();
	}
}
