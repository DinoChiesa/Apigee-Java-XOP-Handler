// Copyright 2018-2022 Google LLC.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// ------------------------------------------------------------------

package com.google.apigee.edgecallouts;

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.message.Message;
import com.apigee.flow.message.MessageContext;
import com.google.apigee.IOUtil;
import com.google.apigee.xml.XmlUtils;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import mockit.Mock;
import mockit.MockUp;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class TestXopHandler {
  private static final String testDataDir = "src/test/resources";

  MessageContext msgCtxt;
  InputStream messageContentStream;
  Message message;
  ExecutionContext exeCtxt;
  final boolean verbose = false;

  private static String stringify(Object value) {
    if (value != null) return value.toString();
    return "-null-";
  }

  private static InputStream fileInputStream(String relativeFileName) throws IOException {
    return new FileInputStream(Paths.get(testDataDir, relativeFileName).toFile());
  }

  private static File inputStreamToTempFile(InputStream in) throws IOException {
    File file = File.createTempFile("xophandler-test-", ".tmp");
    try (OutputStream out = new FileOutputStream(file)) {
      byte[] buf = new byte[1024];
      int len;
      while ((len = in.read(buf)) > 0) {
        out.write(buf, 0, len);
      }
      in.close();
    }

    return file;
  }

  private static String sha256(InputStream is) throws IOException, NoSuchAlgorithmException {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    try (DigestInputStream dis = new DigestInputStream(is, md)) {
      byte[] buf = new byte[0x1000];
      while (true) {
        int r = dis.read(buf);
        if (r == -1) {
          break;
        }
      }
    }
    return toHexString(md.digest());
  }

  private static String toHexString(byte[] bytes) {
    return IntStream.range(0, bytes.length)
        .mapToObj(i -> String.format("%02X", bytes[i]))
        .collect(Collectors.joining());
  }

  @BeforeMethod()
  public void beforeMethod() {

    msgCtxt =
        new MockUp<MessageContext>() {
          private Map variables;

          public void $init() {
            variables = new HashMap();
          }

          @Mock()
          public <T> T getVariable(final String name) {
            if (variables == null) {
              variables = new HashMap();
            }
            if (name.equals("message")) {
              return (T) message;
            }
            if (verbose) {
              System.out.printf("getVariable(%s) = %s\n", name, stringify(variables.get(name)));
            }
            return (T) variables.get(name);
          }

          @Mock()
          public boolean setVariable(final String name, final Object value) {
            if (variables == null) {
              variables = new HashMap();
            }
            if (name.equals("message.content")) {
              if (value instanceof String) {
                messageContentStream =
                    new ByteArrayInputStream(((String) value).getBytes(StandardCharsets.UTF_8));
              } else if (value instanceof InputStream) {
                messageContentStream = (InputStream) value;
              }
            }
            if (verbose) {
              System.out.printf("setVariable(%s) <= %s\n", name, value.toString());
            }
            variables.put(name, value);
            return true;
          }

          @Mock()
          public boolean removeVariable(final String name) {
            if (variables == null) {
              variables = new HashMap();
            }
            if (variables.containsKey(name)) {
              variables.remove(name);
            }
            return true;
          }

          @Mock()
          public Message getMessage() {
            return message;
          }
        }.getMockInstance();

    exeCtxt = new MockUp<ExecutionContext>() {}.getMockInstance();

    message =
        new MockUp<Message>() {
          @Mock()
          public InputStream getContentAsStream() {
            return messageContentStream;
          }

          @Mock()
          public String getHeader(String name) {
            if (verbose) {
              System.out.printf("\ngetHeader(%s)\n", name);
            }
            return (String) msgCtxt.getVariable("message.header." + name.toLowerCase());
          }

          @Mock()
          public void setContent(InputStream is) {
            // System.out.printf("\n** setContent(Stream)\n");
            messageContentStream = is;
          }

          @Mock()
          public void setContent(String content) {
            // System.out.printf("\n** setContent(String)\n");
            messageContentStream =
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
          }

          @Mock()
          public String getContent() {
            // System.out.printf("\n** getContent()\n");
            try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
              IOUtil.copy(messageContentStream, os);
              return new String(os.toByteArray(), StandardCharsets.UTF_8);
            } catch (Exception ex1) {
              return null;
            }
          }
        }.getMockInstance();
  }

  private static final String msg1 =
      "--@@MIME_BOUNDARY@@\n"
          + "Content-Type: @@XMLCTYPE@@; charset=UTF-8\n"
          + "Content-Transfer-Encoding: 8bit\n"
          + "Content-ID: @@CONTENT_ID_1@@\n"
          + "\n"
          + "<S:Envelope xmlns:S='http://schemas.xmlsoap.org/soap/envelope/'>\n"
          + "  <S:Header>\n"
          + "    <wsse:Security\n"
          + "       "
          + " xmlns:wsse='http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd'>\n"
          + "      <wsse:UsernameToken\n"
          + "         "
          + " xmlns:wsse='http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd'>\n"
          + "        <wsse:Username\n"
          + "           "
          + " xmlns:wsse='http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd'>XXXXXX</wsse:Username>\n"
          + "        <wsse:Password\n"
          + "           "
          + " xmlns:wsse='http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd'>XXXXXX</wsse:Password>\n"
          + "      </wsse:UsernameToken>\n"
          + "    </wsse:Security>\n"
          + "  </S:Header>\n"
          + "  <S:Body>\n"
          + "    <GenericRequest\n"
          + "        xmlns='http://www.oracle.com/UCM' webKey='cs'>\n"
          + "      <Service IdcService='CHECKIN_UNIVERSAL'>\n"
          + "        <Document>\n"
          + "          <Field name='UserDateFormat'>iso8601</Field>\n"
          + "          <Field name='UserTimeZone'>UTC</Field>\n"
          + "          <Field name='dDocName'>201807111403445918-1-464</Field>\n"
          + "          <Field name='dSecurityGroup'>FAFusionImportExport</Field>\n"
          + "          <Field name='dDocAccount'>hcm$/dataloader$/import$</Field>\n"
          + "          <Field name='dDocType'>Application</Field>\n"
          + "          <Field"
          + " name='dDocTitle'>201807111403445918_76_I228_1_ValueSet_Budget_Center_ID_Independent.zip</Field>\n"
          + "          <File name='primaryFile'"
          + " href='201807111403445918_76_I228_1_ValueSet_Budget_Center_ID_Independent.zip'>\n"
          + "            <Contents>\n"
          + "              <Include\n"
          + "                  xmlns='http://www.w3.org/2004/08/xop/include'"
          + " href='cid:0b83cd6b-af15-45d2-bbda-23895de2a73d'/>\n"
          + "            </Contents>\n"
          + "          </File>\n"
          + "        </Document>\n"
          + "      </Service>\n"
          + "    </GenericRequest>\n"
          + "  </S:Body>\n"
          + "</S:Envelope>\n"
          + "\n"
          + "--@@MIME_BOUNDARY@@\n"
          + "Content-Type: application/zip\n"
          + "Content-Transfer-Encoding: binary\n"
          + "Content-ID: <0b83cd6b-af15-45d2-bbda-23895de2a73d>\n"
          + "\n"
          + "...binary zip data...\n"
          + "\n"
          + "--@@MIME_BOUNDARY@@--\n"
          + "\n";

  private static final String msg2 =
      "--@@MIME_BOUNDARY@@\n"
          + "Content-Type: @@XMLCTYPE@@; charset=UTF-8\n"
          + "Content-Transfer-Encoding: 8bit\n"
          + "Content-ID: @@CONTENT_ID_1@@\n"
          + "\n"
          + "<soap:Envelope\n"
          + " xmlns:soap='http://www.w3.org/2003/05/soap-envelope'\n"
          + " xmlns:xop='http://www.w3.org/2004/08/xop/include'\n"
          + " xmlns:xop-mime='http://www.w3.org/2005/05/xmlmime'>\n"
          + " <soap:Body>\n"
          + " <submitClaim>\n"
          + "  <accountNumber>5XJ45-3B2</accountNumber>\n"
          + "  <eventType>accident</eventType>\n"
          + "  <image xop-mime:content-type='image/bmp'><xop:Include"
          + " href='cid:image@insurance.com'/></image>\n"
          + " </submitClaim>\n"
          + " </soap:Body>\n"
          + "</soap:Envelope>\n"
          + "\n"
          + "--@@MIME_BOUNDARY@@\n"
          + "Content-Type: image/bmp\n"
          + "Content-Transfer-Encoding: binary\n"
          + "Content-ID: <image@insurance.com>\n"
          + "\n"
          + "...binary BMP image...\n"
          + "\n"
          + "--@@MIME_BOUNDARY@@--\n"
          + "\n";

  @Test
  public void parseMessage() throws Exception {
    final String outerCtypeTemplate =
        "Multipart/Related; "
            + "boundary=@@MIME_BOUNDARY@@; "
            + "type='@@START@@'; "
            + "start='@@CONTENT_ID_1@@'";

    String[] innerXmlCtypes = {"soap", "xop"};
    IntStream.range(0, innerXmlCtypes.length)
        .forEach(
            i -> {
              final String xmlCtype =
                  "application/@@CTYPE@@+xml".replaceAll("@@CTYPE@@", innerXmlCtypes[i]);
              final String boundary = UUID.randomUUID().toString().replaceAll("-", "");
              final String contentId1 = String.format("<%s>", UUID.randomUUID().toString());

              msgCtxt.setVariable("message.header.mime-version", "1.0");
              msgCtxt.setVariable(
                  "message.header.content-type",
                  outerCtypeTemplate
                      .replaceAll("@@MIME_BOUNDARY@@", boundary)
                      .replaceAll("@@START@@", contentId1)
                      .replaceAll("@@XMLCTYPE@@", xmlCtype));

              msgCtxt.setVariable(
                  "message.content",
                  msg1.replaceAll("@@MIME_BOUNDARY@@", boundary)
                      .replaceAll("@@CONTENT_ID_1@@", contentId1)
                      .replaceAll("@@XMLCTYPE@@", xmlCtype));

              Properties props = new Properties();
              props.put("source", "message");
              props.put("debug", "true");

              XopHandler callout = new XopHandler(props);

              // execute and retrieve output
              ExecutionResult actualResult = callout.execute(msgCtxt, exeCtxt);
              ExecutionResult expectedResult = ExecutionResult.SUCCESS;
              Assert.assertEquals(actualResult, expectedResult, "ExecutionResult");

              // check result and output
              Object error = msgCtxt.getVariable("xop_error");
              Assert.assertNull(error, "error");

              Object stacktrace = msgCtxt.getVariable("xop_stacktrace");
              Assert.assertNull(stacktrace, "stacktrace");

              // cannot directly reference message.content with the mocked MessageContext
              // Object output = msgCtxt.getVariable("message.content");
              Message msg = msgCtxt.getMessage();
              Object output = msg.getContent();
              Assert.assertNotNull(output, "no output");
            });
  }

  @Test
  public void withBogusAction() throws Exception {
    final String outerCtypeTemplate =
        "Multipart/Related; "
            + "boundary=@@MIME_BOUNDARY@@; "
            + "type='@@XMLCTYPE@@'; "
            + "start='@@START@@'";

    final String boundary = UUID.randomUUID().toString();
    final String contentId1 = String.format("<%s>", UUID.randomUUID().toString());
    final String xmlCtype = "application/soap+xml";
    msgCtxt.setVariable("message.header.mime-version", "1.0");
    msgCtxt.setVariable(
        "message.header.content-type",
        outerCtypeTemplate
            .replaceAll("@@MIME_BOUNDARY@@", boundary)
            .replaceAll("@@START@@", contentId1)
            .replaceAll("@@XMLCTYPE@@", xmlCtype));

    msgCtxt.setVariable(
        "message.content",
        msg1.replaceAll("@@MIME_BOUNDARY@@", boundary)
            .replaceAll("@@CONTENT_ID_1@@", contentId1)
            .replaceAll("@@XMLCTYPE@@", xmlCtype));

    Properties props = new Properties();
    props.put("source", "message");
    props.put("action", "bogus");
    props.put("debug", "true");

    XopHandler callout = new XopHandler(props);

    // execute and retrieve output
    ExecutionResult actualResult = callout.execute(msgCtxt, exeCtxt);
    ExecutionResult expectedResult = ExecutionResult.ABORT;
    Assert.assertEquals(actualResult, expectedResult, "ExecutionResult");

    // check result and output
    Object error = msgCtxt.getVariable("xop_error");
    Assert.assertNotNull(error, "error");
    Assert.assertEquals(error, "specify a valid action.");
  }

  @Test
  public void withExtractAction() throws Exception {
    final String outerCtypeTemplate =
        "Multipart/Related; "
            + "boundary=@@MIME_BOUNDARY@@; "
            + "type='@@XMLCTYPE@@'; "
            + "start='@@START@@'";

    final String boundary = UUID.randomUUID().toString();
    final String contentId1 = String.format("<%s>", UUID.randomUUID().toString());
    msgCtxt.setVariable("message.header.mime-version", "1.0");
    msgCtxt.setVariable(
        "message.header.content-type",
        outerCtypeTemplate
            .replaceAll("@@MIME_BOUNDARY@@", boundary)
            .replaceAll("@@START@@", contentId1)
            .replaceAll("@@XMLCTYPE@@", "application/soap+xml"));

    msgCtxt.setVariable(
        "message.content",
        msg1.replaceAll("@@MIME_BOUNDARY@@", boundary)
            .replaceAll("@@CONTENT_ID_1@@", contentId1)
            .replaceAll("@@XMLCTYPE@@", "application/soap+xml"));

    Properties props = new Properties();
    props.put("source", "message");
    props.put("action", "extract_soap");
    props.put("debug", "true");

    XopHandler callout = new XopHandler(props);

    // execute and retrieve output
    ExecutionResult actualResult = callout.execute(msgCtxt, exeCtxt);
    ExecutionResult expectedResult = ExecutionResult.SUCCESS;
    Assert.assertEquals(actualResult, expectedResult, "ExecutionResult");

    // check result and output
    Object error = msgCtxt.getVariable("xop_error");
    Assert.assertNull(error, "error");

    Object stacktrace = msgCtxt.getVariable("xop_stacktrace");
    Assert.assertNull(stacktrace, "stacktrace");

    String xml = msgCtxt.getVariable("xop_extracted_xml");
    Assert.assertNotNull(xml, "no extracted content");
    Document xmlDoc = XmlUtils.parseXml(xml);
    Assert.assertNotNull(xmlDoc, "cannot instantiate XML document");
  }

  @Test
  public void withEmbedAction() throws Exception {
    final String outerCtypeTemplate =
        "Multipart/Related; "
            + "boundary=@@MIME_BOUNDARY@@; "
            + "type='@@XMLCTYPE@@'; "
            + "start='@@START@@'";

    final String boundary = UUID.randomUUID().toString();
    final String contentId1 = String.format("<%s>", UUID.randomUUID().toString());
    msgCtxt.setVariable("message.header.mime-version", "1.0");
    msgCtxt.setVariable(
        "message.header.content-type",
        outerCtypeTemplate
            .replaceAll("@@MIME_BOUNDARY@@", boundary)
            .replaceAll("@@START@@", contentId1)
            .replaceAll("@@XMLCTYPE@@", "application/soap+xml"));

    msgCtxt.setVariable(
        "message.content",
        msg1.replaceAll("@@MIME_BOUNDARY@@", boundary)
            .replaceAll("@@CONTENT_ID_1@@", contentId1)
            .replaceAll("@@XMLCTYPE@@", "application/soap+xml"));

    Properties props = new Properties();
    props.put("source", "message");
    props.put("action", "TRANSFORM_TO_EMBEDDED");
    props.put("debug", "true");

    XopHandler callout = new XopHandler(props);

    // execute and retrieve output
    ExecutionResult actualResult = callout.execute(msgCtxt, exeCtxt);
    ExecutionResult expectedResult = ExecutionResult.SUCCESS;
    Assert.assertEquals(actualResult, expectedResult, "ExecutionResult");

    // check result and output
    Object error = msgCtxt.getVariable("xop_error");
    Assert.assertNull(error, "error");

    Object stacktrace = msgCtxt.getVariable("xop_stacktrace");
    Assert.assertNull(stacktrace, "stacktrace");

    Message msg = msgCtxt.getMessage();
    Object output = msg.getContent();
    Assert.assertNotNull(output, "no output");

    // System.out.printf("Result:\n%s\n", (String) output);

    Document doc = XmlUtils.parseXml((String) output);
    Assert.assertNotNull(doc, "cannot instantiate XML document");
    NodeList nl = doc.getElementsByTagNameNS("http://www.w3.org/2004/08/xop/include", "Include");
    Assert.assertEquals(nl.getLength(), 0, "Include elements");

    nl = doc.getElementsByTagNameNS("http://www.oracle.com/UCM", "Contents");
    Assert.assertEquals(nl.getLength(), 1, "Contents element");

    String encodedString = nl.item(0).getTextContent();
    byte[] decodedBytes = Base64.getDecoder().decode(encodedString);

    // In THIS PARTICULAR CASE, the decodedBytes will form a string.
    String decodedString = new String(decodedBytes, StandardCharsets.UTF_8);
    Assert.assertEquals("...binary zip data...\n", decodedString, "decoded string");
  }

  @Test
  public void embedPdf() throws Exception {
    final String outerCtypeTemplate =
        "Multipart/Related; "
            + "boundary=@@MIME_BOUNDARY@@; "
            + "type='@@XMLCTYPE@@'; "
            + "start='<@@START@@>'";

    final String boundary = UUID.randomUUID().toString();
    final String contentId1 = UUID.randomUUID().toString();
    final String xmlCtype = "application/soap+xml";
    msgCtxt.setVariable("message.header.mime-version", "1.0");
    msgCtxt.setVariable(
        "message.header.content-type",
        outerCtypeTemplate
            .replaceAll("@@MIME_BOUNDARY@@", boundary)
            .replaceAll("@@START@@", contentId1)
            .replaceAll("@@XMLCTYPE@@", xmlCtype));

    // assemble content
    List<InputStream> streams = new ArrayList<InputStream>();
    final String leaderTemplate =
        ""
            + "--@@MIME_BOUNDARY@@\n"
            + "Content-Type: @@XMLCTYPE@@; charset=UTF-8\n"
            + "Content-Transfer-Encoding: 8bit\n"
            + "Content-ID: <@@CONTENT_ID_1@@>\n"
            + "\n";
    final String leader =
        leaderTemplate
            .replaceAll("@@MIME_BOUNDARY@@", boundary)
            .replaceAll("@@CONTENT_ID_1@@", contentId1)
            .replaceAll("@@XMLCTYPE@@", xmlCtype);

    streams.add(new ByteArrayInputStream(leader.getBytes(StandardCharsets.UTF_8)));
    final String contentId2 = UUID.randomUUID().toString();
    String xmlContent =
        new String(
            IOUtil.readAllBytes(fileInputStream("acord-example-pdf-attachment.xml")),
            StandardCharsets.UTF_8);

    streams.add(
        new ByteArrayInputStream(
            xmlContent.replaceAll("@@CONTENT_ID@@", contentId2).getBytes(StandardCharsets.UTF_8)));

    final String dividerTemplate =
        "\n"
            + "--@@MIME_BOUNDARY@@\n"
            + "Content-Type: application/pdf\n"
            + "Content-Transfer-Encoding: binary\n"
            + "Content-ID: <@@CONTENT_ID_2@@>\n"
            + "\n";

    final String divider =
        dividerTemplate
            .replaceAll("@@MIME_BOUNDARY@@", boundary)
            .replaceAll("@@CONTENT_ID_2@@", contentId2);
    streams.add(new ByteArrayInputStream(divider.getBytes(StandardCharsets.UTF_8)));

    String sha256_before = sha256(fileInputStream("Apigee-API-Jam-Datasheet.pdf"));

    streams.add(fileInputStream("Apigee-API-Jam-Datasheet.pdf"));

    final String trailerTemplate = "\n--@@MIME_BOUNDARY@@--\n";
    final String trailer = trailerTemplate.replaceAll("@@MIME_BOUNDARY@@", boundary);
    streams.add(new ByteArrayInputStream(trailer.getBytes(StandardCharsets.UTF_8)));

    SequenceInputStream contentInputStream =
        new SequenceInputStream(Collections.enumeration(streams));

    // write this content to a file, just for diagnostics purposes
    File contentFile = inputStreamToTempFile(contentInputStream);
    // System.out.printf("\n\nContent File for Input:\n%s\n", (String)
    // contentFile.getAbsolutePath());

    // now slurp up the file, to use for content
    msgCtxt.getMessage().setContent(new FileInputStream(contentFile));

    Properties props = new Properties();
    props.put("source", "message");
    props.put("action", "TRANSFORM_TO_EMBEDDED");
    props.put("debug", "true");

    XopHandler callout = new XopHandler(props);

    // execute and retrieve output
    ExecutionResult actualResult = callout.execute(msgCtxt, exeCtxt);
    ExecutionResult expectedResult = ExecutionResult.SUCCESS;
    Assert.assertEquals(actualResult, expectedResult, "ExecutionResult");

    // check result and output
    Object error = msgCtxt.getVariable("xop_error");
    Assert.assertNull(error, "error");

    Object stacktrace = msgCtxt.getVariable("xop_stacktrace");
    Assert.assertNull(stacktrace, "stacktrace");

    Message msg = msgCtxt.getMessage();
    String output = (String) msg.getContent();
    Assert.assertNotNull(output, "no output");

    // System.out.printf("Result:\n%s\n", output);

    Document xmlDoc = XmlUtils.parseXml(output);
    Assert.assertNotNull(xmlDoc, "cannot instantiate XML document");

    // compare the before and after content
    NodeList nl =
        xmlDoc.getElementsByTagNameNS(
            "http://ACORD.org/Standards/Life/2", "AttachmentData64Binary");
    Assert.assertEquals(nl.getLength(), 1, "AttachmentData64Binary element");

    String base64String = nl.item(0).getTextContent();

    byte[] decodedContent =
        Base64.getDecoder().decode(base64String.getBytes(StandardCharsets.UTF_8));
    String sha256_after = sha256(new ByteArrayInputStream(decodedContent));
    Assert.assertEquals(sha256_before, sha256_after);
  }

  @Test
  public void unacceptableContentType() throws Exception {
    final String outerCtypeTemplate =
        "Multipart/Related; "
            + "boundary=@@MIME_BOUNDARY@@; "
            + "type='@@XMLCTYPE@@'; "
            + "start='@@START@@'";

    final String boundary = UUID.randomUUID().toString();
    final String contentId1 = "<claim@insurance.com>";
    msgCtxt.setVariable("message.header.mime-version", "1.0");
    msgCtxt.setVariable(
        "message.header.content-type",
        outerCtypeTemplate
            .replaceAll("@@MIME_BOUNDARY@@", boundary)
            .replaceAll("@@START@@", contentId1)
            .replaceAll("@@XMLCTYPE@@", "application/soap+xml"));

    msgCtxt.setVariable(
        "message.content",
        msg2.replaceAll("@@MIME_BOUNDARY@@", boundary)
            .replaceAll("@@CONTENT_ID_1@@", contentId1)
            .replaceAll("@@XMLCTYPE@@", "application/soap+xml"));

    Properties props = new Properties();
    props.put("source", "message");
    props.put("debug", "true");

    XopHandler callout = new XopHandler(props);

    // execute and retrieve output
    ExecutionResult actualResult = callout.execute(msgCtxt, exeCtxt);
    ExecutionResult expectedResult = ExecutionResult.ABORT;
    Assert.assertEquals(actualResult, expectedResult, "ExecutionResult");

    // check result and output
    Object error = msgCtxt.getVariable("xop_error");
    Assert.assertNotNull(error, "error");

    Object stacktrace = msgCtxt.getVariable("xop_stacktrace");
    Assert.assertNull(stacktrace, "stacktrace");

    // cannot directly reference message.content with the mocked MessageContext
    // Object output = msgCtxt.getVariable("message.content");
    Message msg = msgCtxt.getMessage();
    Object output = msg.getContent();
    Assert.assertNotNull(output, "no output");
  }

  @Test
  public void acceptableContentType() throws Exception {
    final String outerCtypeTemplate =
        "Multipart/Related; "
            + "boundary=@@MIME_BOUNDARY@@; "
            + "type='@@XMLCTYPE@@'; "
            + "start='@@START@@'";

    final String boundary = UUID.randomUUID().toString();
    final String contentId1 = "<claim@insurance.com>";
    msgCtxt.setVariable("message.header.mime-version", "1.0");
    msgCtxt.setVariable(
        "message.header.content-type",
        outerCtypeTemplate
            .replaceAll("@@MIME_BOUNDARY@@", boundary)
            .replaceAll("@@START@@", contentId1)
            .replaceAll("@@XMLCTYPE@@", "application/soap+xml"));

    msgCtxt.setVariable(
        "message.content",
        msg2.replaceAll("@@MIME_BOUNDARY@@", boundary)
            .replaceAll("@@CONTENT_ID_1@@", contentId1)
            .replaceAll("@@XMLCTYPE@@", "application/soap+xml"));

    Properties props = new Properties();
    props.put("source", "message");
    props.put("part2-ctypes", "image/jpeg, image/bmp");
    props.put("debug", "true");

    XopHandler callout = new XopHandler(props);

    // execute and retrieve output
    ExecutionResult actualResult = callout.execute(msgCtxt, exeCtxt);
    ExecutionResult expectedResult = ExecutionResult.SUCCESS;
    Assert.assertEquals(actualResult, expectedResult, "ExecutionResult");

    // check result and output
    Object error = msgCtxt.getVariable("xop_error");
    Assert.assertNull(error, "error");

    Object stacktrace = msgCtxt.getVariable("xop_stacktrace");
    Assert.assertNull(stacktrace, "stacktrace");
  }

  @Test
  public void multipleAttachments() throws Exception {
    final String relativeFileName = "acord-example-multiple-pdf.bin";

    try (InputStream input =
            new FileInputStream(Paths.get(testDataDir, relativeFileName).toFile());
        InputStreamReader charReader = new InputStreamReader(input);
        BufferedReader reader = new BufferedReader(charReader)) {
      String headerLine = reader.readLine().trim();

      Pattern contentTypeHeaderPattern = Pattern.compile("(?i)^content-type *: *(.+)$");
      Matcher m = contentTypeHeaderPattern.matcher(headerLine);
      if (!m.matches()) {
        throw new IllegalStateException("unexpected content-id header in test input");
      }
      msgCtxt.setVariable("message.header.content-type", m.group(1).trim());
      msgCtxt.setVariable("message.header.mime-version", "1.0");
      msgCtxt.setVariable(
          "message.content",
          reader.lines().collect(Collectors.joining(System.lineSeparator())).trim());

      Properties props = new Properties();
      props.put("source", "message");
      props.put("action", "TRANSFORM_TO_EMBEDDED");
      // props.put("debug", "true");

      XopHandler callout = new XopHandler(props);

      // execute and retrieve output
      ExecutionResult actualResult = callout.execute(msgCtxt, exeCtxt);
      ExecutionResult expectedResult = ExecutionResult.SUCCESS;
      Assert.assertEquals(actualResult, expectedResult, "ExecutionResult");

      // check result and output
      Object error = msgCtxt.getVariable("xop_error");
      Assert.assertNull(error, "error");

      Object stacktrace = msgCtxt.getVariable("xop_stacktrace");
      Assert.assertNull(stacktrace, "stacktrace");
      Message msg = msgCtxt.getMessage();
      String output = msg.getContent();
      Assert.assertNotNull(output, "no output");

      Document doc = XmlUtils.parseXml(output);
      NodeList nl = doc.getElementsByTagNameNS("http://www.w3.org/2004/08/xop/include", "Include");
      Assert.assertEquals(nl.getLength(), 0, "Include elements");

      nl =
          doc.getElementsByTagNameNS("http://ACORD.org/Standards/Life/2", "AttachmentData64Binary");
      Assert.assertEquals(nl.getLength(), 2, "Attachment elements");

      for (int ix = 0; ix < nl.getLength(); ix++) {
        Element attachmentElt = (Element) nl.item(ix);
        NodeList children = attachmentElt.getChildNodes();
        Assert.assertEquals(children.getLength(), 1, "Attachment elements");
        Node child = children.item(0);
        Assert.assertEquals(Node.TEXT_NODE, child.getNodeType(), "Child node");
      }
    }
  }

  @Test
  public void urlEncodedContentId() throws Exception {
    Arrays.asList("acord-with-url-encoded-content-id.bin", "encoded-content-ex2.bin")
      .stream()
      .forEach( relativeFileName -> {

          try (InputStream input =
               new FileInputStream(Paths.get(testDataDir, relativeFileName).toFile());
               InputStreamReader charReader = new InputStreamReader(input);
               BufferedReader reader = new BufferedReader(charReader)) {
            String headerLine = reader.readLine().trim();

            Pattern contentTypeHeaderPattern = Pattern.compile("(?i)^content-type *: *(.+)$");
            Matcher m = contentTypeHeaderPattern.matcher(headerLine);
            if (!m.matches()) {
              throw new IllegalStateException("unexpected content-id header in test input");
            }
            msgCtxt.setVariable("message.header.content-type", m.group(1).trim());
            msgCtxt.setVariable("message.header.mime-version", "1.0");
            msgCtxt.setVariable(
                                "message.content",
                                reader.lines().collect(Collectors.joining(System.lineSeparator())).trim());

            Properties props = new Properties();
            props.put("source", "message");
            props.put("action", "TRANSFORM_TO_EMBEDDED");
            // props.put("debug", "true");

            XopHandler callout = new XopHandler(props);

            // execute and retrieve output
            ExecutionResult actualResult = callout.execute(msgCtxt, exeCtxt);
            ExecutionResult expectedResult = ExecutionResult.SUCCESS;
            Assert.assertEquals(actualResult, expectedResult, "ExecutionResult");

            // check result and output
            Object error = msgCtxt.getVariable("xop_error");
            Assert.assertNull(error, "error");

            Object stacktrace = msgCtxt.getVariable("xop_stacktrace");
            Assert.assertNull(stacktrace, "stacktrace");
            Message msg = msgCtxt.getMessage();
            String output = msg.getContent();
            Assert.assertNotNull(output, "no output");

            Document doc = XmlUtils.parseXml(output);
            NodeList nl = doc.getElementsByTagNameNS("http://www.w3.org/2004/08/xop/include", "Include");
            Assert.assertEquals(nl.getLength(), 0, "Include elements");

            nl =
              doc.getElementsByTagNameNS("http://ACORD.org/Standards/Life/2", "AttachmentData64Binary");
            Assert.assertEquals(nl.getLength(), 1, "Attachment elements");

            Element attachmentElt = (Element) nl.item(0);
            NodeList children = attachmentElt.getChildNodes();
            Assert.assertEquals(children.getLength(), 1, "Attachment elements");
            Node child = children.item(0);
            Assert.assertEquals(Node.TEXT_NODE, child.getNodeType(), "Child node");
          }
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        });

  }
}
