package me.bechberger.testorder.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class MavenPluginConfigKeysTest {

    @Test
    void showMethodOrderExplainIsRecognizedAsKnownProperty() {
        Properties props = new Properties();
        props.setProperty("testorder.showMethodOrder.explain", "true");

        List<String> warnings = MavenPluginConfigKeys.findUnknownProperties(props);

        assertThat(warnings).isEmpty();
    }
}
