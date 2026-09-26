package com.naro.aws;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.autoscaling.AutoScalingGroup;
import software.amazon.awscdk.services.autoscaling.HealthChecks;
import software.amazon.awscdk.services.autoscaling.Ec2HealthChecksOptions;
import software.amazon.awscdk.services.autoscaling.UpdatePolicy;
import software.amazon.awscdk.services.budgets.CfnBudget;
import software.amazon.awscdk.services.codebuild.*;
import software.amazon.awscdk.services.codeconnections.CfnConnection;
import software.amazon.awscdk.services.codedeploy.*;
import software.amazon.awscdk.services.codepipeline.*;
import software.amazon.awscdk.services.codepipeline.actions.*;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.elasticloadbalancingv2.*;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.rds.*;
import software.amazon.awscdk.services.s3.*;
import software.constructs.Construct;

/** One application host and one database. Secrets are referenced, never synthesized. */
public final class NaroStack extends Stack {
    private static final String PARAMETER_PREFIX = "/naro/prod";

    public NaroStack(Construct scope, String id, StackProps props) {
        super(scope, id, props);
        Tags.of(this).add("Project", "naro");
        Tags.of(this).add("Environment", "lab");

        String frontendOrigin = validatedOrigin(context("frontendOrigin", "http://localhost:3000"));
        String hostname = context("publicHostname", "");
        String certificateArn = context("certificateArn", "");
        if (hostname.isBlank() != certificateArn.isBlank()) {
            throw new IllegalArgumentException("Use publicHostname e certificateArn juntos para HTTPS.");
        }
        if (!hostname.isBlank() && !hostname.matches("[A-Za-z0-9.-]+")) {
            throw new IllegalArgumentException("publicHostname deve conter somente o domínio, sem URL/caminho.");
        }
        boolean https = !certificateArn.isBlank();

        Vpc vpc = Vpc.Builder.create(this, "Vpc")
                .vpcName("naro-lab")
                .ipAddresses(IpAddresses.cidr("10.30.0.0/16"))
                .maxAzs(2).natGateways(0)
                .subnetConfiguration(List.of(
                        SubnetConfiguration.builder().name("public").subnetType(SubnetType.PUBLIC).cidrMask(24).build(),
                        SubnetConfiguration.builder().name("database").subnetType(SubnetType.PRIVATE_ISOLATED).cidrMask(24).build()))
                .build();
        SecurityGroup albSg = securityGroup(vpc, "AlbSecurityGroup", "Public ALB; only backend ports outbound");
        SecurityGroup hostSg = securityGroup(vpc, "HostSecurityGroup", "Application host; only ALB inbound, no SSH");
        SecurityGroup databaseSg = securityGroup(vpc, "DatabaseSecurityGroup", "Private PostgreSQL; only application host inbound");
        albSg.addIngressRule(Peer.anyIpv4(), Port.tcp(80), "Public HTTP entry/HTTPS redirect");
        if (https) albSg.addIngressRule(Peer.anyIpv4(), Port.tcp(443), "Public HTTPS entry");
        for (int port : List.of(8080, 8081, 9000)) {
            hostSg.addIngressRule(albSg, Port.tcp(port), "ALB only");
            albSg.addEgressRule(hostSg, Port.tcp(port), "Application/Keycloak/Keycloak health");
        }
        // HTTPS for AWS APIs, image registries, package repositories; HTTP for OS mirrors.
        hostSg.addEgressRule(Peer.anyIpv4(), Port.tcp(443), "AWS APIs and software downloads");
        hostSg.addEgressRule(Peer.anyIpv4(), Port.tcp(80), "OS repository mirrors");
        hostSg.addEgressRule(databaseSg, Port.tcp(5432), "PostgreSQL");
        databaseSg.addIngressRule(hostSg, Port.tcp(5432), "Application and Keycloak only");

        DatabaseInstance database = DatabaseInstance.Builder.create(this, "Database")
                .databaseName("naro")
                .engine(DatabaseInstanceEngine.postgres(PostgresInstanceEngineProps.builder()
                        .version(PostgresEngineVersion.of("17.11", "17")).build()))
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE4_GRAVITON, InstanceSize.MICRO))
                .credentials(Credentials.fromPassword("naro_admin",
                        SecretValue.ssmSecure(PARAMETER_PREFIX + "/database/master-password")))
                .vpc(vpc).vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PRIVATE_ISOLATED).build())
                .availabilityZone(vpc.getAvailabilityZones().getFirst())
                .securityGroups(List.of(databaseSg)).publiclyAccessible(false)
                .multiAz(false).allocatedStorage(20).storageType(StorageType.GP3).storageEncrypted(true)
                .backupRetention(Duration.days(1)).deleteAutomatedBackups(true)
                .enablePerformanceInsights(false).monitoringInterval(Duration.seconds(0))
                .autoMinorVersionUpgrade(true).allowMajorVersionUpgrade(false)
                .deletionProtection(false).removalPolicy(RemovalPolicy.SNAPSHOT)
                .build();

        Bucket publications = privateBucket("Publications", "naro-publications-" + getAccount() + "-" + getRegion());
        publications.addCorsRule(CorsRule.builder().allowedOrigins(List.of(frontendOrigin))
                .allowedMethods(List.of(HttpMethods.GET, HttpMethods.HEAD))
                .allowedHeaders(List.of("*")).exposedHeaders(List.of("ETag")).maxAge(3600).build());
        Bucket artifacts = privateBucket("Artifacts", "naro-artifacts-" + getAccount() + "-" + getRegion());
        // Keep successful revisions: CodeDeploy needs them to populate a replacement EC2 host.
        artifacts.addLifecycleRule(LifecycleRule.builder().id("AbortIncompleteUploads")
                .abortIncompleteMultipartUploadAfter(Duration.days(1)).build());

        LogGroup applicationLogs = logGroup("ApplicationLogs", "/naro/prod/application");
        Role hostRole = Role.Builder.create(this, "HostRole").assumedBy(new ServicePrincipal("ec2.amazonaws.com")).build();
        hostRole.addToPolicy(policy(List.of("s3:ListBucket"), List.of(publications.getBucketArn())));
        hostRole.addToPolicy(policy(List.of("s3:GetObject", "s3:PutObject", "s3:DeleteObject"), List.of(publications.arnForObjects("*"))));
        artifactAccess(hostRole, artifacts, false);
        hostRole.addToPolicy(policy(List.of("ssm:GetParameters"), List.of(
                parameterArn("/database/master-password"), parameterArn("/database/password"),
                parameterArn("/keycloak/db-password"), parameterArn("/keycloak/admin-password"), parameterArn("/keycloak/client-secret"))));
        // SSM registration/session channels do not support resource-scoped IAM permissions.
        hostRole.addToPolicy(policy(List.of("ssm:UpdateInstanceInformation", "ssmmessages:CreateControlChannel",
                "ssmmessages:CreateDataChannel", "ssmmessages:OpenControlChannel", "ssmmessages:OpenDataChannel"), List.of("*")));
        hostRole.addToPolicy(policy(List.of("logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"),
                List.of(applicationLogs.getLogGroupArn())));

        ApplicationLoadBalancer alb = ApplicationLoadBalancer.Builder.create(this, "LoadBalancer")
                .vpc(vpc).vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PUBLIC).build())
                .internetFacing(true).securityGroup(albSg).deletionProtection(false)
                .dropInvalidHeaderFields(true).idleTimeout(Duration.seconds(120)).build();
        String publicOrigin = (https ? "https://" : "http://") + (hostname.isBlank() ? alb.getLoadBalancerDnsName() : hostname);
        UserData userData = UserData.forLinux();
        userData.addCommands(
                "set -euo pipefail", "umask 077",
                "dnf install -y java-25-amazon-corretto-headless docker postgresql17 python3 wget logrotate awscli-2 amazon-cloudwatch-agent",
                "systemctl enable --now docker amazon-ssm-agent",
                "install -d -m 0700 /etc/naro",
                "cat > /etc/naro/infrastructure.env <<'NARO_INFRA'",
                "AWS_REGION=" + getRegion(), "NARO_PARAMETER_PREFIX=" + PARAMETER_PREFIX,
                "DB_HOST=" + database.getDbInstanceEndpointAddress(), "DB_PORT=5432", "DB_NAME=naro",
                "DB_ADMIN_USER=naro_admin", "DB_USER=naro_app", "KEYCLOAK_DB_NAME=keycloak", "KEYCLOAK_DB_USER=keycloak",
                "KEYCLOAK_ADMIN=naro-admin", "S3_BUCKET_NAME=" + publications.getBucketName(),
                "PUBLIC_ORIGIN=" + publicOrigin, "FRONTEND_ORIGIN=" + frontendOrigin,
                "CLOUDWATCH_LOG_GROUP=" + applicationLogs.getLogGroupName(), "NARO_INFRA",
                "chmod 0600 /etc/naro/infrastructure.env",
                "cd /tmp",
                "wget -q https://aws-codedeploy-" + getRegion() + ".s3." + getRegion() + ".amazonaws.com/latestv2/install -O install-codedeploy",
                "chmod 0700 install-codedeploy", "./install-codedeploy auto", "rm install-codedeploy",
                "sed -i '/^:max_revisions:/d' /etc/codedeploy-agent/conf/codedeployagent.yml",
                "printf '\\n:max_revisions: 2\\n' >> /etc/codedeploy-agent/conf/codedeployagent.yml",
                "systemctl enable codedeploy-agent", "systemctl restart codedeploy-agent");
        LaunchTemplate launchTemplate = LaunchTemplate.Builder.create(this, "HostTemplate")
                .machineImage(MachineImage.latestAmazonLinux2023(AmazonLinux2023ImageSsmParameterProps.builder()
                        .cpuType(AmazonLinuxCpuType.ARM_64).build()))
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE4_GRAVITON, InstanceSize.SMALL))
                .role(hostRole).securityGroup(hostSg).userData(userData)
                .requireImdsv2(true).httpPutResponseHopLimit(1).cpuCredits(CpuCredits.STANDARD)
                .detailedMonitoring(false)
                .blockDevices(List.of(BlockDevice.builder().deviceName("/dev/xvda")
                        .volume(BlockDeviceVolume.ebs(20, EbsDeviceOptions.builder()
                                .volumeType(EbsDeviceVolumeType.GP3).encrypted(true).deleteOnTermination(true).build())).build()))
                .build();
        AutoScalingGroup host = AutoScalingGroup.Builder.create(this, "Host")
                // The single application host shares the database AZ to avoid cross-AZ traffic charges.
                .vpc(vpc).vpcSubnets(SubnetSelection.builder().subnets(List.of(vpc.getPublicSubnets().getFirst())).build())
                .launchTemplate(launchTemplate).minCapacity(1).desiredCapacity(1).maxCapacity(1)
                .minHealthyPercentage(0).maxHealthyPercentage(100)
                // EC2 health avoids replacing the host while its first application revision is not deployed yet.
                .healthChecks(HealthChecks.ec2(Ec2HealthChecksOptions.builder().gracePeriod(Duration.minutes(15)).build()))
                .updatePolicy(UpdatePolicy.rollingUpdate(software.amazon.awscdk.services.autoscaling.RollingUpdateOptions.builder()
                        .minInstancesInService(0).maxBatchSize(1).pauseTime(Duration.minutes(5)).build()))
                .build();
        Tags.of(host).add("Name", "naro-lab");

        ApplicationTargetGroup backend = targetGroup("BackendTargets", vpc, 8080, "/actuator/health", "8080", host);
        ApplicationTargetGroup keycloak = targetGroup("KeycloakTargets", vpc, 8081, "/health/ready", "9000", host);
        ApplicationListener listener;
        if (https) {
            listener = alb.addListener("Https", BaseApplicationListenerProps.builder().port(443)
                    .certificates(List.of(ListenerCertificate.fromArn(certificateArn)))
                    .sslPolicy(SslPolicy.RECOMMENDED_TLS).open(false).defaultTargetGroups(List.of(backend)).build());
            alb.addListener("Http", BaseApplicationListenerProps.builder().port(80).open(false)
                    .defaultAction(ListenerAction.redirect(RedirectOptions.builder().protocol("HTTPS").port("443").permanent(true).build())).build());
        } else {
            listener = alb.addListener("Http", BaseApplicationListenerProps.builder().port(80).open(false)
                    .defaultTargetGroups(List.of(backend)).build());
        }
        // Administration is accessible only through Session Manager port forwarding.
        listener.addAction("BlockKeycloakAdministration", AddApplicationActionProps.builder().priority(10)
                .conditions(List.of(ListenerCondition.pathPatterns(List.of("/auth/admin*", "/auth/realms/master*"))))
                .action(ListenerAction.fixedResponse(403, FixedResponseOptions.builder().contentType("text/plain").messageBody("Forbidden").build())).build());
        listener.addTargetGroups("Keycloak", AddApplicationTargetGroupsProps.builder().priority(20)
                .conditions(List.of(ListenerCondition.pathPatterns(List.of(
                        "/auth/realms/social", "/auth/realms/social/*", "/auth/resources/*"))))
                .targetGroups(List.of(keycloak)).build());
        listener.addAction("BlockOtherKeycloakPaths", AddApplicationActionProps.builder().priority(30)
                .conditions(List.of(ListenerCondition.pathPatterns(List.of("/auth", "/auth/*"))))
                .action(ListenerAction.fixedResponse(404, FixedResponseOptions.builder().contentType("text/plain").messageBody("Not found").build())).build());

        String connectionArn = context("connectionArn", "");
        if (connectionArn.isBlank()) {
            connectionArn = CfnConnection.Builder.create(this, "GithubConnection")
                    .connectionName("naro-github").providerType("GitHub").build().getAttrConnectionArn();
        }
        Pipeline pipeline = createPipeline(host, artifacts, connectionArn, vpc, launchTemplate, hostRole, hostSg);
        createBudget();

        output("ApplicationUrl", publicOrigin);
        output("HealthUrl", publicOrigin + "/actuator/health");
        output("KeycloakIssuer", publicOrigin + "/auth/realms/social");
        output("ConnectionArn", connectionArn);
        output("PipelineName", pipeline.getPipelineName());
        output("AutoScalingGroupName", host.getAutoScalingGroupName());
        output("DatabaseEndpoint", database.getDbInstanceEndpointAddress());
        output("PublicationsBucket", publications.getBucketName());
        output("ArtifactsBucket", artifacts.getBucketName());
        output("ApplicationLogGroup", applicationLogs.getLogGroupName());
    }

    private Pipeline createPipeline(AutoScalingGroup host, Bucket artifacts, String connectionArn,
                                    Vpc vpc, LaunchTemplate launchTemplate, Role hostRole, SecurityGroup hostSg) {
        LogGroup buildLogs = logGroup("BuildLogs", "/naro/prod/codebuild");
        Role buildRole = Role.Builder.create(this, "BuildRole").assumedBy(new ServicePrincipal("codebuild.amazonaws.com")).build();
        artifactAccess(buildRole, artifacts, true);
        buildRole.addToPolicy(policy(List.of("logs:CreateLogStream", "logs:PutLogEvents"), List.of(buildLogs.getLogGroupArn())));
        buildRole.addToPolicy(policy(List.of("codebuild:CreateReportGroup", "codebuild:CreateReport", "codebuild:UpdateReport",
                "codebuild:BatchPutTestCases"), List.of(serviceArn("codebuild", "report-group/naro-build-*"))));
        PipelineProject build = PipelineProject.Builder.create(this, "Build")
                .projectName("naro-build").role(buildRole.withoutPolicyUpdates())
                .environment(BuildEnvironment.builder().buildImage(LinuxBuildImage.STANDARD_7_0)
                        .computeType(ComputeType.SMALL).privileged(true).build())
                .buildSpec(BuildSpec.fromSourceFilename("buildspec.yml"))
                .timeout(Duration.minutes(30)).queuedTimeout(Duration.minutes(30)).concurrentBuildLimit(1)
                .logging(LoggingOptions.builder().cloudWatch(CloudWatchLoggingOptions.builder().logGroup(buildLogs).build()).build())
                .build();
        Role deployRole = Role.Builder.create(this, "DeployRole").assumedBy(new ServicePrincipal("codedeploy.amazonaws.com")).build();
        deployRole.addToPolicy(policy(List.of("ec2:DescribeInstances", "ec2:DescribeInstanceStatus", "tag:GetResources",
                "autoscaling:DescribeAutoScalingGroups", "autoscaling:DescribeLifecycleHooks", "autoscaling:DescribeAutoScalingInstances"), List.of("*")));
        deployRole.addToPolicy(policy(List.of("autoscaling:CompleteLifecycleAction", "autoscaling:DeleteLifecycleHook",
                "autoscaling:PutLifecycleHook", "autoscaling:RecordLifecycleActionHeartbeat"), List.of(
                host.getAutoScalingGroupArn(),
                // CodeDeploy tests its service role against this fictitious ASG before creating
                // the deployment group. Observed in CloudTrail; no additional ASG is created.
                serviceArn("autoscaling", "autoScalingGroup:*:autoScalingGroupName/CodeDeployTestAPICall"))));
        deployRole.addToPolicy(policy(List.of("autoscaling:SuspendProcesses", "autoscaling:ResumeProcesses"),
                List.of(host.getAutoScalingGroupArn())));
        // CodeDeploy validates these when attaching an ASG that uses a launch template.
        String templateArn = serviceArn("ec2", "launch-template/" + launchTemplate.getLaunchTemplateId());
        List<String> launchResources = new ArrayList<>(List.of(templateArn,
                "arn:" + getPartition() + ":ec2:" + getRegion() + "::image/" + launchTemplate.getImageId(),
                serviceArn("ec2", "security-group/" + hostSg.getSecurityGroupId()),
                serviceArn("ec2", "instance/*"), serviceArn("ec2", "volume/*"), serviceArn("ec2", "network-interface/*")));
        vpc.getPublicSubnets().forEach(subnet -> launchResources.add(serviceArn("ec2", "subnet/" + subnet.getSubnetId())));
        deployRole.addToPolicy(PolicyStatement.Builder.create().actions(List.of("ec2:RunInstances"))
                .resources(launchResources).conditions(Map.of("ArnEquals", Map.of("ec2:LaunchTemplate", templateArn),
                        "StringEqualsIfExists", Map.of("ec2:InstanceType", "t4g.small"))).build());
        deployRole.addToPolicy(PolicyStatement.Builder.create().actions(List.of("ec2:CreateTags"))
                .resources(List.of(serviceArn("ec2", "instance/*"), serviceArn("ec2", "volume/*")))
                .conditions(Map.of("StringEquals", Map.of("ec2:CreateAction", "RunInstances", "aws:RequestTag/Project", "naro"))).build());
        deployRole.addToPolicy(PolicyStatement.Builder.create().actions(List.of("iam:PassRole"))
                .resources(List.of(hostRole.getRoleArn())).conditions(Map.of("StringEquals", Map.of("iam:PassedToService", "ec2.amazonaws.com"))).build());
        ServerApplication application = ServerApplication.Builder.create(this, "DeployApplication").applicationName("naro").build();
        ServerDeploymentGroup deployment = ServerDeploymentGroup.Builder.create(this, "DeploymentGroup")
                .application(application).deploymentGroupName("naro-lab").role(deployRole)
                .autoScalingGroups(List.of(host)).installAgent(false)
                .deploymentConfig(ServerDeploymentConfig.ALL_AT_ONCE)
                .autoRollback(AutoRollbackConfig.builder().failedDeployment(true).stoppedDeployment(true).build())
                .build();
        // The L2 attaches AWSCodeDeployRole even with a supplied role. Our in-place deployment
        // needs only the policies above, not its SNS/ELB/broad Auto Scaling permissions.
        ((CfnRole) deployRole.getNode().getDefaultChild()).addPropertyDeletionOverride("ManagedPolicyArns");
        deployment.getNode().addDependency(deployRole);
        Role pipelineRole = Role.Builder.create(this, "PipelineRole").assumedBy(new ServicePrincipal("codepipeline.amazonaws.com")).build();
        artifactAccess(pipelineRole, artifacts, true);
        // The connection source writes artifacts with the bucket-owner-full-control ACL.
        pipelineRole.addToPolicy(policy(List.of("s3:PutObjectAcl"), List.of(artifacts.arnForObjects("*"))));
        // CodePipeline's connection source documents both namespaces for new and legacy connections.
        for (String connectionService : List.of("codeconnections", "codestar-connections")) {
            pipelineRole.addToPolicy(PolicyStatement.Builder.create().actions(List.of(connectionService + ":UseConnection"))
                    .resources(List.of(connectionArn)).conditions(Map.of("StringEqualsIfExists", Map.of(
                            connectionService + ":FullRepositoryId", context("repositoryOwner", "leonardovilelaaws-del") + "/" + context("repositoryName", "naro_back"),
                            connectionService + ":BranchName", context("repositoryBranch", "master"))))
                    .build());
        }
        pipelineRole.addToPolicy(policy(List.of("codebuild:StartBuild", "codebuild:StopBuild", "codebuild:BatchGetBuilds"), List.of(build.getProjectArn())));
        pipelineRole.addToPolicy(policy(List.of("codedeploy:GetApplicationRevision", "codedeploy:RegisterApplicationRevision"), List.of(application.getApplicationArn())));
        pipelineRole.addToPolicy(policy(List.of("codedeploy:CreateDeployment", "codedeploy:GetDeployment"), List.of(deployment.getDeploymentGroupArn())));
        pipelineRole.addToPolicy(policy(List.of("codedeploy:GetDeploymentConfig"), List.of(serviceArn("codedeploy", "deploymentconfig:CodeDeployDefault.AllAtOnce"))));
        Pipeline pipeline = Pipeline.Builder.create(this, "Pipeline").pipelineName("naro")
                .pipelineType(PipelineType.V1).artifactBucket(artifacts).role(pipelineRole.withoutPolicyUpdates())
                .usePipelineRoleForActions(true)
                .crossAccountKeys(false).restartExecutionOnUpdate(false).build();
        pipeline.getNode().addDependency(pipelineRole);
        Artifact source = new Artifact("Source");
        Artifact jar = new Artifact("Application");
        pipeline.addStage(StageOptions.builder().stageName("Source").actions(List.of(CodeStarConnectionsSourceAction.Builder.create()
                .actionName("GitHub").connectionArn(connectionArn).owner(context("repositoryOwner", "leonardovilelaaws-del"))
                .repo(context("repositoryName", "naro_back")).branch(context("repositoryBranch", "master"))
                .output(source).triggerOnPush(true).build())).build());
        pipeline.addStage(StageOptions.builder().stageName("Build").actions(List.of(CodeBuildAction.Builder.create()
                .actionName("TestAndPackage").project(build).input(source).outputs(List.of(jar)).build())).build());
        pipeline.addStage(StageOptions.builder().stageName("Deploy").actions(List.of(CodeDeployServerDeployAction.Builder.create()
                .actionName("DeployToEc2").deploymentGroup(deployment).input(jar).build())).build());
        return pipeline;
    }

    private void createBudget() {
        String existingBudgetName = context("existingBudgetName", "");
        if (!existingBudgetName.isBlank()) {
            output("BudgetName", existingBudgetName);
            return;
        }
        CfnParameter email = CfnParameter.Builder.create(this, "BudgetEmail").type("String")
                .description("Email que receberá alertas de custo. Não altera/desliga recursos.")
                .allowedPattern("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").build();
        CfnBudget.Builder.create(this, "MonthlyBudget")
                .budget(CfnBudget.BudgetDataProperty.builder().budgetName("naro-lab-monthly")
                        .budgetType("COST").timeUnit("MONTHLY")
                        .budgetLimit(CfnBudget.SpendProperty.builder().amount(25).unit("USD").build()).build())
                .notificationsWithSubscribers(List.of(budgetNotification(email, "ACTUAL", 50), budgetNotification(email, "ACTUAL", 80),
                        budgetNotification(email, "ACTUAL", 100), budgetNotification(email, "FORECASTED", 100)))
                .build();
        output("BudgetName", "naro-lab-monthly");
    }

    private CfnBudget.NotificationWithSubscribersProperty budgetNotification(CfnParameter email, String type, int threshold) {
        return CfnBudget.NotificationWithSubscribersProperty.builder()
                .notification(CfnBudget.NotificationProperty.builder().notificationType(type).comparisonOperator("GREATER_THAN")
                        .thresholdType("PERCENTAGE").threshold(threshold).build())
                .subscribers(List.of(CfnBudget.SubscriberProperty.builder().subscriptionType("EMAIL").address(email.getValueAsString()).build())).build();
    }

    private ApplicationTargetGroup targetGroup(String id, Vpc vpc, int port, String healthPath, String healthPort, AutoScalingGroup host) {
        return ApplicationTargetGroup.Builder.create(this, id).vpc(vpc).port(port).protocol(ApplicationProtocol.HTTP)
                .targets(List.of(host)).deregistrationDelay(Duration.seconds(15))
                .healthCheck(software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck.builder()
                        .path(healthPath).port(healthPort).healthyHttpCodes("200")
                        .interval(Duration.seconds(30)).timeout(Duration.seconds(10)).healthyThresholdCount(2).unhealthyThresholdCount(3).build())
                .build();
    }

    private SecurityGroup securityGroup(Vpc vpc, String id, String description) {
        return SecurityGroup.Builder.create(this, id).vpc(vpc).description(description).allowAllOutbound(false).build();
    }

    private Bucket privateBucket(String id, String name) {
        return Bucket.Builder.create(this, id).bucketName(name).blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .encryption(BucketEncryption.S3_MANAGED).enforceSsl(true).versioned(false)
                .objectOwnership(ObjectOwnership.BUCKET_OWNER_ENFORCED).removalPolicy(RemovalPolicy.RETAIN).build();
    }

    private LogGroup logGroup(String id, String name) {
        return LogGroup.Builder.create(this, id).logGroupName(name).retention(RetentionDays.ONE_WEEK)
                .removalPolicy(RemovalPolicy.DESTROY).build();
    }

    private PolicyStatement policy(List<String> actions, List<String> resources) {
        return PolicyStatement.Builder.create().actions(actions).resources(resources).build();
    }

    private void artifactAccess(Role role, Bucket bucket, boolean write) {
        role.addToPolicy(policy(List.of("s3:ListBucket", "s3:GetBucketLocation", "s3:GetBucketVersioning"), List.of(bucket.getBucketArn())));
        role.addToPolicy(policy(write ? List.of("s3:GetObject", "s3:GetObjectVersion", "s3:PutObject", "s3:AbortMultipartUpload")
                : List.of("s3:GetObject", "s3:GetObjectVersion"), List.of(bucket.arnForObjects("*"))));
    }

    private String serviceArn(String service, String resource) {
        return "arn:" + getPartition() + ":" + service + ":" + getRegion() + ":" + getAccount() + ":" + resource;
    }

    private String parameterArn(String suffix) {
        return "arn:" + getPartition() + ":ssm:" + getRegion() + ":" + getAccount() + ":parameter" + PARAMETER_PREFIX + suffix;
    }

    private String context(String key, String fallback) {
        Object value = getNode().tryGetContext(key);
        if (value == null) {
            // cdk.context.json keeps external selections local to the AWS account/region.
            // Explicit -c values still win, and a different account never reuses this selection.
            Object deployment = getNode().tryGetContext("naro:deployment:" + getAccount() + ":" + getRegion());
            if (deployment != null && !(deployment instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("O contexto naro:deployment:<conta>:<região> deve ser um objeto JSON.");
            }
            if (deployment instanceof Map<?, ?> settings) {
                value = settings.get(key);
            }
        }
        return value == null ? fallback : value.toString();
    }

    private static String validatedOrigin(String value) {
        URI uri = URI.create(value);
        if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
            throw new IllegalArgumentException("frontendOrigin deve ser uma origem HTTP(S), sem barra final/caminho.");
        }
        return value;
    }

    private void output(String name, String value) {
        CfnOutput.Builder.create(this, name).value(value).build();
    }
}
