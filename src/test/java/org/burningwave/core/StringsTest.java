package org.burningwave.core;

import java.util.regex.Pattern;

import org.burningwave.core.common.Strings;
import org.junit.jupiter.api.Test;

public class StringsTest extends BaseTest {
	
	@Test
	public void extractTestOne() {
		testNotNull(() ->
			Strings.extractAllGroups(Pattern.compile("\\$\\{([\\w\\d\\.]*)\\}([\\w]*)"), "${${ciao.Asdf.1}prova${ciao}}").get(1).get(0)
		);
	}
}
