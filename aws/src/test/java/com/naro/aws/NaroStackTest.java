package com.naro.aws;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.AppProps;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.LegacyStackSynthesizer;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

import static org.junit.jupiter.api.Assertions.*;

class NaroStackTest {
    private Template template(Map<String, Object> context) {
        App app = new App(AppProps.builder().context(context).build());
        NaroStack stack = new NaroStack(app, "NaroTest", StackProps.builder()
                .env(Environment.builder().account("123456789012").region("us-east-1").build())
                .synthesizer(new LegacyStackSynthesizer()).build());
        return Template.fromStack(stack);
    }

    @Test
    void labCannotScaleOutOrCreateExpensiveAdditionalServices() {
        Template template = template(Map.of());
        for (String type : List.of("AWS::EC2::NatGateway", "AWS::EC2::EIP", "AWS::Lambda::Function",
                "AWS::SNS::Topic", "AWS::SQS::Queue", "AWS::ECS::Cluster", "AWS::KMS::Key", "AWS::CloudFront::Distribution")) {
            template.resourceCountIs(type, 0);
        }
        template.resourceCountIs("AWS::ElasticLoadBalancingV2::LoadBalancer", 1);
        template.resourceCountIs("AWS::AutoScaling::AutoScalingGroup", 1);
        template.resourceCountIs("AWS::RDS::DBInstance", 1);
        template.resourceCountIs("AWS::S3::Bucket", 2); // Publications + required pipeline artifacts.
        template.hasResourceProperties("AWS::AutoScaling::AutoScalingGroup", Map.of(
                "MinSize", "1", "DesiredCapacity", "1", "MaxSize", "1",
                "InstanceMaintenancePolicy", Map.of("MinHealthyPercentage", 0, "MaxHealthyPercentage", 100)));
        Map<String, Object> asg = map(template.findResources("AWS::AutoScaling::AutoScalingGroup").values().iterator().next().get("Properties"));
        List<?> hostSubnets = (List<?>) asg.get("VPCZoneIdentifier");
        assertEquals(1, hostSubnets.size());
        String hostSubnetId = (String) map(hostSubnets.getFirst()).get("Ref");
        Map<String, Object> hostSubnet = map(template.findResources("AWS::EC2::Subnet").get(hostSubnetId).get("Properties"));
        Map<String, Object> database = map(template.findResources("AWS::RDS::DBInstance").values().iterator().next().get("Properties"));
        assertEquals(database.get("AvailabilityZone"), hostSubnet.get("AvailabilityZone"));
        template.hasResourceProperties("AWS::EC2::LaunchTemplate", Map.of("LaunchTemplateData", Match.objectLike(Map.of(
                "InstanceType", "t4g.small", "CreditSpecification", Map.of("CpuCredits", "standard"),
                "MetadataOptions", Match.objectLike(Map.of("HttpTokens", "required")),
                "BlockDeviceMappings", Match.arrayWith(List.of(Match.objectLike(Map.of("Ebs", Match.objectLike(Map.of(
                        "Encrypted", true, "VolumeSize", 20, "VolumeType", "gp3"))))))))));
    }

    @Test
    void databaseAndBucketsStayPrivateAndDeletionDoesNotDiscardData() {
        Template template = template(Map.of());
        template.hasResourceProperties("AWS::RDS::DBInstance", Map.of(
                "PubliclyAccessible", false, "MultiAZ", false, "StorageEncrypted", true,
                "DBInstanceClass", "db.t4g.micro", "BackupRetentionPeriod", 1,
                "EnablePerformanceInsights", false, "DeletionProtection", false,
                "MasterUserPassword", "{{resolve:ssm-secure:/naro/prod/database/master-password}}"));
        template.hasResource("AWS::RDS::DBInstance", Map.of("DeletionPolicy", "Snapshot", "UpdateReplacePolicy", "Snapshot"));
        template.allResourcesProperties("AWS::S3::Bucket", Map.of(
                "PublicAccessBlockConfiguration", Map.of("BlockPublicAcls", true, "BlockPublicPolicy", true,
                        "IgnorePublicAcls", true, "RestrictPublicBuckets", true),
                "BucketEncryption", Map.of("ServerSideEncryptionConfiguration", List.of(Map.of(
                        "ServerSideEncryptionByDefault", Map.of("SSEAlgorithm", "AES256"))))));
        template.allResources("AWS::S3::Bucket", Map.of("DeletionPolicy", "Retain", "UpdateReplacePolicy", "Retain"));
        template.allResourcesProperties("AWS::Logs::LogGroup", Map.of("RetentionInDays", 7));
    }

    @Test
    void internetCanOnlyReachTheLoadBalancerAndKeycloakAdminIsBlocked() {
        Template template = template(Map.of());
        List<Map<String, Object>> ingress = new ArrayList<>();
        for (Map<String, Object> resource : template.findResources("AWS::EC2::SecurityGroupIngress").values()) {
            ingress.add(map(resource.get("Properties")));
        }
        for (Map<String, Object> resource : template.findResources("AWS::EC2::SecurityGroup").values()) {
            Object inline = map(resource.get("Properties")).get("SecurityGroupIngress");
            if (inline instanceof List<?> rules) rules.forEach(rule -> ingress.add(map(rule)));
        }
        int publiclyOpenRules = 0;
        for (Map<String, Object> rule : ingress) {
            if ("0.0.0.0/0".equals(rule.get("CidrIp")) || "::/0".equals(rule.get("CidrIpv6"))) {
                publiclyOpenRules++;
                assertEquals(80, ((Number) rule.get("FromPort")).intValue());
                assertEquals(80, ((Number) rule.get("ToPort")).intValue());
            } else {
                assertNotNull(rule.get("SourceSecurityGroupId"));
            }
        }
        assertEquals(1, publiclyOpenRules);
        template.hasResourceProperties("AWS::ElasticLoadBalancingV2::ListenerRule", Map.of(
                "Priority", 10, "Actions", Match.arrayWith(List.of(Match.objectLike(Map.of("Type", "fixed-response",
                        "FixedResponseConfig", Match.objectLike(Map.of("StatusCode", "403"))))))));
        template.hasResourceProperties("AWS::ElasticLoadBalancingV2::ListenerRule", Map.of(
                "Priority", 20, "Conditions", Match.arrayWith(List.of(Match.objectLike(Map.of(
                        "Field", "path-pattern", "PathPatternConfig", Map.of("Values", List.of(
                                "/auth/realms/social", "/auth/realms/social/*", "/auth/resources/*"))))))));
        template.hasResourceProperties("AWS::ElasticLoadBalancingV2::ListenerRule", Map.of(
                "Priority", 30, "Actions", Match.arrayWith(List.of(Match.objectLike(Map.of("Type", "fixed-response",
                        "FixedResponseConfig", Match.objectLike(Map.of("StatusCode", "404"))))))));
        template.hasResourceProperties("AWS::ElasticLoadBalancingV2::TargetGroup", Map.of(
                "Port", 8081, "HealthCheckPort", "9000", "HealthCheckPath", "/health/ready"));
        template.hasResourceProperties("AWS::ElasticLoadBalancingV2::TargetGroup", Map.of(
                "Port", 8080, "HealthCheckPath", "/actuator/health"));
    }

    @Test
    void pipelineUsesTheRealBranchAndNoBuildRoleCanReadProductionSecrets() {
        Template template = template(Map.of());
        template.resourceCountIs("AWS::IAM::Role", 4);
        template.allResourcesProperties("AWS::IAM::Role", Map.of("ManagedPolicyArns", Match.absent()));
        template.hasResourceProperties("AWS::CodePipeline::Pipeline", Map.of("PipelineType", "V1",
                "Stages", Match.arrayWith(List.of(Match.objectLike(Map.of("Name", "Source", "Actions", Match.arrayWith(List.of(
                        Match.objectLike(Map.of("Configuration", Match.objectLike(Map.of("FullRepositoryId", "leonardovilelaaws-del/naro_back",
                                "BranchName", "master", "DetectChanges", true))))))))))));
        for (Map.Entry<String, Map<String, Object>> entry : template.findResources("AWS::IAM::Policy").entrySet()) {
            Map<String, Object> policy = map(map(entry.getValue().get("Properties")).get("PolicyDocument"));
            for (Object raw : (List<?>) policy.get("Statement")) {
                Map<String, Object> statement = map(raw);
                Object actionValue = statement.get("Action");
                List<?> actions = actionValue instanceof List<?> list ? list : List.of(actionValue);
                assertFalse(actions.contains("*"));
                assertFalse(actions.stream().map(Object::toString).anyMatch(action -> action.endsWith(":*")));
                if (actions.stream().anyMatch(action -> Set.of("ssm:GetParameters", "s3:DeleteObject").contains(action))) {
                    assertTrue(entry.getKey().startsWith("HostRole"));
                    assertNotEquals("*", statement.get("Resource"));
                }
                assertFalse(actions.contains("ssm:PutParameter"));
                assertFalse(actions.contains("secretsmanager:GetSecretValue"));
                assertFalse(actions.contains("s3:PutObjectLegalHold"));
                if (actions.contains("autoscaling:CompleteLifecycleAction")) {
                    assertTrue(entry.getKey().startsWith("DeployRole"));
                    assertInstanceOf(List.class, statement.get("Resource"));
                    List<?> resources = (List<?>) statement.get("Resource");
                    assertEquals(2, resources.size());
                    assertTrue(resources.toString().contains("CodeDeployTestAPICall"));
                    assertFalse(resources.contains("*"));
                }
            }
        }
        template.hasResourceProperties("AWS::CodeDeploy::DeploymentGroup", Map.of("AutoRollbackConfiguration",
                Match.objectLike(Map.of("Enabled", true, "Events", Match.arrayWith(List.of("DEPLOYMENT_FAILURE"))))));
    }

    @Test
    void existingConnectionAndBudgetAreReusedAndHttpsRedirects() {
        Template template = template(Map.of(
                "existingBudgetName", "existing-account-budget",
                "connectionArn", "arn:aws:codeconnections:us-east-1:123456789012:connection/11111111-1111-1111-1111-111111111111",
                "certificateArn", "arn:aws:acm:us-east-1:123456789012:certificate/11111111-1111-1111-1111-111111111111",
                "publicHostname", "api.example.com"));
        template.resourceCountIs("AWS::CodeConnections::Connection", 0);
        template.resourceCountIs("AWS::Budgets::Budget", 0);
        template.resourceCountIs("AWS::ElasticLoadBalancingV2::Listener", 2);
        template.hasResourceProperties("AWS::ElasticLoadBalancingV2::Listener", Map.of("Port", 80,
                "DefaultActions", Match.arrayWith(List.of(Match.objectLike(Map.of("Type", "redirect",
                        "RedirectConfig", Match.objectLike(Map.of("Protocol", "HTTPS", "Port", "443"))))))));
        template.hasOutput("ApplicationUrl", Map.of("Value", "https://api.example.com"));
    }

    @Test
    void accountRegionContextReusesExternalResourcesWithoutCommandLineFlags() {
        String connectionArn = "arn:aws:codeconnections:us-east-1:123456789012:connection/11111111-1111-1111-1111-111111111111";
        Template template = template(Map.of("naro:deployment:123456789012:us-east-1", Map.of(
                "connectionArn", connectionArn, "existingBudgetName", "account-lab-budget")));
        template.resourceCountIs("AWS::CodeConnections::Connection", 0);
        template.resourceCountIs("AWS::Budgets::Budget", 0);
        template.hasOutput("ConnectionArn", Map.of("Value", connectionArn));
        template.hasOutput("BudgetName", Map.of("Value", "account-lab-budget"));
    }

    @Test
    void anotherAccountOrRegionDoesNotReuseTheLocalResourceSelection() {
        Map<String, Object> settings = Map.of(
                "connectionArn", "arn:aws:codeconnections:us-west-2:999999999999:connection/11111111-1111-1111-1111-111111111111",
                "existingBudgetName", "unrelated-budget");
        Template template = template(Map.of(
                "naro:deployment:999999999999:us-east-1", settings,
                "naro:deployment:123456789012:us-west-2", settings));
        template.resourceCountIs("AWS::CodeConnections::Connection", 1);
        template.resourceCountIs("AWS::Budgets::Budget", 1);
    }

    @Test
    void explicitContextOverridesTheAccountRegionSelection() {
        String selectedArn = "arn:aws:codeconnections:us-east-1:123456789012:connection/11111111-1111-1111-1111-111111111111";
        String overrideArn = "arn:aws:codeconnections:us-east-1:123456789012:connection/22222222-2222-2222-2222-222222222222";
        Template template = template(Map.of(
                "naro:deployment:123456789012:us-east-1", Map.of(
                        "connectionArn", selectedArn, "existingBudgetName", "selected-budget"),
                "connectionArn", overrideArn, "existingBudgetName", "override-budget"));
        template.resourceCountIs("AWS::CodeConnections::Connection", 0);
        template.resourceCountIs("AWS::Budgets::Budget", 0);
        template.hasOutput("ConnectionArn", Map.of("Value", overrideArn));
        template.hasOutput("BudgetName", Map.of("Value", "override-budget"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
