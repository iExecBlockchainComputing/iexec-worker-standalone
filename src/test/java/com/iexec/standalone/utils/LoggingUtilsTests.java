package com.iexec.standalone.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class LoggingUtilsTests {

    final String MESSAGE = "Hello, World!";
    final String HASHTAG_SEQUENCE = new String(new char[MESSAGE.length()]).replace('\0', '#');
    final String SPACE_SEQUENCE = new String(new char[MESSAGE.length()]).replace('\0', ' ');

    @Test
    public void testGetHighlightedMessage() {
        String message = "Hello, World!";
        String expected = "\n" +
                "##" + HASHTAG_SEQUENCE + "##\n" +
                "# " + SPACE_SEQUENCE   + " #\n" +
                "# " + MESSAGE          + " #\n" +
                "# " + SPACE_SEQUENCE   + " #\n" +
                "##" + HASHTAG_SEQUENCE + "##\n" +
                "\n";
        String actual = LoggingUtils.getHighlightedMessage(message);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    public void testGetHeaderFooterHashMessage() {
        String expected = "\n" +
                "##" + HASHTAG_SEQUENCE + "##\n" +
                MESSAGE + "\n" +
                "##" + HASHTAG_SEQUENCE + "##\n" +
                "\n";
        String actual = LoggingUtils.getHeaderFooterHashMessage(MESSAGE);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    public void testPrettifyDeveloperLogs() {
        String iexecInTree = "iexec_in tree content";
        String iexecOutTree = "iexec_out tree content";
        String stdout = "stdout content";
        String stderr = "stderr content";
        String expected = "\n" +
                "#################### DEV MODE ####################\n" +
                "iexec_in folder\n" +
                "--------------------\n" +
                "iexec_in tree content\n" +
                "\n" +
                "iexec_out folder\n" +
                "--------------------\n" +
                "iexec_out tree content\n" +
                "\n" +
                "stdout\n" +
                "--------------------\n" +
                "stdout content\n" +
                "\n" +
                "stderr\n" +
                "--------------------\n" +
                "stderr content\n" +
                "#################### DEV MODE ####################\n" +
                "\n";
        String actual = LoggingUtils.prettifyDeveloperLogs(iexecInTree, iexecOutTree, stdout, stderr);
        assertThat(actual).isEqualTo(expected);
    }
}
