// Copyright 2018-2021 Google Inc.
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
import com.google.apigee.xml.XmlUtils;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import mockit.Mock;
import mockit.MockUp;
import org.apache.commons.io.IOUtils;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.w3c.dom.Document;

public class TestXopHandler {
  private static final String testDataDir = "src/test/resources/test-data";

  MessageContext msgCtxt;
  InputStream messageContentStream;
  Message message;
  ExecutionContext exeCtxt;

  private static String stringify(Object value) {
    if (value != null) return value.toString();
    return "-null-";
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
            System.out.printf("getVariable(%s) = %s\n", name, stringify(variables.get(name)));
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
            System.out.printf("setVariable(%s) <= %s\n", name, value.toString());
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
            System.out.printf("\ngetHeader(%s)\n", name);
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
            try {
              StringWriter writer = new StringWriter();
              IOUtils.copy(messageContentStream, writer, StandardCharsets.UTF_8);
              return writer.toString();
            } catch (Exception ex1) {
              return null;
            }
          }
        }.getMockInstance();
  }

  private static final String msg1 =
      ""
          + "--MIME_boundary\n"
          + "Content-Type: application/soap+xml; charset=UTF-8\n"
          + "Content-Transfer-Encoding: 8bit\n"
          + "Content-ID: <rootpart@soapui.org>\n"
          + "\n"
          + "<S:Envelope xmlns:S='http://schemas.xmlsoap.org/soap/envelope/'>\n"
          + "  <S:Header>\n"
          + "    <wsse:Security\n"
          + "        xmlns:wsse='http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd'>\n"
          + "      <wsse:UsernameToken\n"
          + "          xmlns:wsse='http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd'>\n"
          + "        <wsse:Username\n"
          + "            xmlns:wsse='http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd'>XXXXXX</wsse:Username>\n"
          + "        <wsse:Password\n"
          + "            xmlns:wsse='http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd'>XXXXXX</wsse:Password>\n"
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
          + "          <Field name='dDocTitle'>201807111403445918_76_I228_1_ValueSet_Budget_Center_ID_Independent.zip</Field>\n"
          + "          <File name='primaryFile' href='201807111403445918_76_I228_1_ValueSet_Budget_Center_ID_Independent.zip'>\n"
          + "            <Contents>\n"
          + "              <Include\n"
          + "                  xmlns='http://www.w3.org/2004/08/xop/include' href='cid:0b83cd6b-af15-45d2-bbda-23895de2a73d'/>\n"
          + "            </Contents>\n"
          + "          </File>\n"
          + "        </Document>\n"
          + "      </Service>\n"
          + "    </GenericRequest>\n"
          + "  </S:Body>\n"
          + "</S:Envelope>\n"
          + "\n"
          + "--MIME_boundary\n"
          + "Content-Type: application/zip\n"
          + "Content-Transfer-Encoding: binary\n"
          + "Content-ID: <0b83cd6b-af15-45d2-bbda-23895de2a73d>\n"
          + "\n"
          + "...binary zip data...\n"
          + "\n"
          + "--MIME_boundary--\n"
          + "\n";

  private static final String msg2 =
      ""
          + "--MIME_boundary\n"
          + "Content-Type: application/soap+xml; charset=UTF-8\n"
          + "Content-Transfer-Encoding: 8bit\n"
          + "Content-ID: <claim@insurance.com>\n"
          + "\n"
          + "<soap:Envelope\n"
          + " xmlns:soap='http://www.w3.org/2003/05/soap-envelope'\n"
          + " xmlns:xop='http://www.w3.org/2004/08/xop/include'\n"
          + " xmlns:xop-mime='http://www.w3.org/2005/05/xmlmime'>\n"
          + " <soap:Body>\n"
          + " <submitClaim>\n"
          + "  <accountNumber>5XJ45-3B2</accountNumber>\n"
          + "  <eventType>accident</eventType>\n"
          + "  <image xop-mime:content-type='image/jpeg'><xop:Include href='cid:image@insurance.com'/></image>\n"
          + " </submitClaim>\n"
          + " </soap:Body>\n"
          + "</soap:Envelope>\n"
          + "\n"
          + "--MIME_boundary\n"
          + "Content-Type: image/jpeg\n"
          + "Content-Transfer-Encoding: binary\n"
          + "Content-ID: <image@insurance.com>\n"
          + "\n"
          + "...binary JPG image...\n"
          + "\n"
          + "--MIME_boundary--\n"
          + "\n";

  @Test
  public void parseMessage() throws Exception {

    msgCtxt.setVariable("message.header.mime-version", "1.0");
    msgCtxt.setVariable(
        "message.header.content-type",
        "Multipart/Related; boundary=MIME_boundary; type='application/soap+xml'; start='<rootpart@soapui.org>'");

    msgCtxt.setVariable("message.content", msg1);

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
  }

  @Test
  public void withBogusAction() throws Exception {

    msgCtxt.setVariable("message.header.mime-version", "1.0");
    msgCtxt.setVariable(
        "message.header.content-type",
        "Multipart/Related; boundary=MIME_boundary; type='application/soap+xml'; start='<rootpart@soapui.org>'");

    msgCtxt.setVariable("message.content", msg1);

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

    msgCtxt.setVariable("message.header.mime-version", "1.0");
    msgCtxt.setVariable(
        "message.header.content-type",
        "Multipart/Related; boundary=MIME_boundary; type='application/soap+xml'; start='<rootpart@soapui.org>'");

    msgCtxt.setVariable("message.content", msg1);

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
  public void parseMessage_withWrongContentType() throws Exception {

    msgCtxt.setVariable("message.header.mime-version", "1.0");
    msgCtxt.setVariable(
        "message.header.content-type",
        "Multipart/Related; boundary=MIME_boundary; type='application/soap+xml'; start='<claim@insurance.com>'");

    msgCtxt.setVariable("message.content", msg2);

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
}
