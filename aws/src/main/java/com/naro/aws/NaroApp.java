package com.naro.aws;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.LegacyStackSynthesizer;
import software.amazon.awscdk.StackProps;

public final class NaroApp {
    private NaroApp() {}

    public static void main(String[] args) {
        App app = new App();
        new NaroStack(app, "NaroLab", StackProps.builder()
                .description("Naro: single-host AWS learning environment, no NAT and no scale-out")
                .env(Environment.builder()
                        .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                        .region(System.getenv().getOrDefault("CDK_DEFAULT_REGION", "us-east-1"))
                        .build())
                // No CDK assets: deployments use the caller credentials, without a bootstrap stack.
                .synthesizer(new LegacyStackSynthesizer())
                .build());
        app.synth();
    }
}
