package com.labcalendar.labcalendarbackend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import static org.assertj.core.api.Assertions.assertThat;

/** Protect the production dependency chain against accidentally bypassing database tests. */
class DeploymentGateTests {
    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) { return (Map<String, Object>) value; }

    private Map<String, Object> workflow(String name) throws Exception {
        try (var input = Files.newInputStream(Path.of(".github/workflows/" + name + ".yml"))) {
            return new Yaml().load(input);
        }
    }

    @Test
    void publishingAndDeploymentRequireTheSharedVerificationToSucceed() throws Exception {
        var jobs = map(workflow("deploy").get("jobs"));
        var verify = map(jobs.get("verify"));
        assertThat(verify.get("uses")).isEqualTo("./.github/workflows/verify.yml");
        assertThat(verify).doesNotContainKeys("continue-on-error", "if");
        var deploy = map(jobs.get("build-and-deploy"));
        assertThat(deploy.get("needs")).isEqualTo("verify");
        assertThat(deploy).doesNotContainKeys("if", "continue-on-error");
    }

    @Test
    @SuppressWarnings("unchecked")
    void bothDatabaseTestCommandsAreMandatoryAndReportsSurviveFailure() throws Exception {
        var job = map(map(workflow("verify").get("jobs")).get("migration-tests"));
        assertThat(job).doesNotContainKeys("continue-on-error", "if");
        var steps = (List<Map<String, Object>>) job.get("steps");
        for (String database : List.of("h2", "mysql")) {
            var test = steps.stream().filter(step -> database.equals(step.get("id"))).findFirst().orElseThrow();
            assertThat(test).doesNotContainKeys("continue-on-error", "if");
            String command = (String) test.get("run");
            assertThat(command).startsWith("./gradlew test ").doesNotContain("||", "-x", "continue");
            var report = steps.stream().filter(step -> ("backend-tests-" + database)
                    .equals(map(step.getOrDefault("with", Map.of())).get("name"))).findFirst().orElseThrow();
            assertThat((String) report.get("if")).contains("always()");
        }
    }
}
