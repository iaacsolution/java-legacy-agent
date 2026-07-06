package com.audensiel.legacy.agent;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

/**
 * Initialise l'exporteur OTLP gRPC vers Phoenix/Arize.
 * Endpoint configuré via OTEL_EXPORTER_OTLP_ENDPOINT (défaut : http://localhost:4317).
 */
public class PipelineTracer {

    private static OpenTelemetrySdk sdk;
    private static Tracer tracer;

    public static void init(String otlpEndpoint) {
        Resource resource = Resource.getDefault().merge(
            Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"), "java-legacy-agent")));

        OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint(otlpEndpoint)
            .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            .build();

        sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .build();

        tracer = sdk.getTracer("com.audensiel.legacy.agent");
    }

    public static Tracer get() {
        return tracer != null ? tracer : OpenTelemetry.noop().getTracer("noop");
    }

    /** À appeler en fin de programme pour vider le BatchSpanProcessor. */
    public static void shutdown() {
        if (sdk != null) sdk.close();
    }
}
