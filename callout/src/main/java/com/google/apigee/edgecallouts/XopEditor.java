// XopEditor.java
//
// Copyright (c) 2018-2020 Google LLC.
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

package com.google.apigee.edgecallouts;

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.execution.spi.Execution;
import com.apigee.flow.message.Message;
import com.apigee.flow.message.MessageContext;
import com.github.danieln.multipart.MultipartInput;
import com.github.danieln.multipart.MultipartOutput;
import com.github.danieln.multipart.PartInput;
import com.github.danieln.multipart.PartOutput;
import com.google.apigee.xml.XPathEvaluator;
import com.google.apigee.xml.XmlUtils;
import com.google.common.primitives.Bytes;
import com.google.common.io.ByteStreams;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.xml.xpath.XPathConstants;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class XopEditor extends CalloutBase implements Execution {
  private static final String varprefix = "xop_";
  private static final boolean wantStringDefault = true;

  public XopEditor(Map properties) {
    super(properties);
  }

  public String getVarnamePrefix() {
    return varprefix;
  }

  private String getSource(MessageContext msgCtxt) throws Exception {
    String source = getSimpleOptionalProperty("source", msgCtxt);
    if (source == null) {
      source = "message";
    }
    return source;
  }

  private static void remove(Node currentNode) {
    // if the adjacent node is empty text, delete it
    Node prevSibling = currentNode.getPreviousSibling();
    if (prevSibling != null
        && prevSibling.getNodeType() == Node.TEXT_NODE
        && prevSibling.getNodeValue().trim().isEmpty()) {
      currentNode.getParentNode().removeChild(prevSibling);
    }
    // delete the node
    currentNode.getParentNode().removeChild(currentNode);
  }

  private static String removeUsernameToken(InputStream in1) throws Exception {
    Document document = XmlUtils.parseXml(in1);

    XPathEvaluator xpe = new XPathEvaluator();
    xpe.registerNamespace("soap", "http://schemas.xmlsoap.org/soap/envelope/");
    xpe.registerNamespace(
        "wsse",
        "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd");
    String xpath = "/soap:Envelope/soap:Header/wsse:Security/wsse:UsernameToken";
    NodeList nodes = (NodeList) xpe.evaluate(xpath, document, XPathConstants.NODESET);
    if (nodes.getLength() == 1) {
      remove(nodes.item(0));
    }
    return XmlUtils.toString(document, true);
  }

  public ExecutionResult execute(final MessageContext msgCtxt, final ExecutionContext execContext) {
    try {
      String source = getSource(msgCtxt);
      Message message = (Message) msgCtxt.getVariable(source);
      if (message == null) {
        throw new IllegalStateException("source message is null.");
      }
      String originalContentType = message.getHeader("content-type");
      MultipartInput mpi =
        new MultipartInput(message.getContentAsStream(), originalContentType);

      Map<String, String> params = MultipartInput.parseParams(originalContentType);
      if (params.get("boundary") == null) {
        throw new IllegalStateException("no boundary found");
      }

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      MultipartOutput mpo = new MultipartOutput(out, originalContentType, params.get("boundary"));

      // 1. extract and transform the XML here
      PartInput partInput1 = mpi.nextPart();
      String ctype1 = partInput1.getContentType();
      if (ctype1 == null) {
        throw new IllegalStateException("no content-type found (part1)");
      }
      if (!ctype1.startsWith("application/soap+xml") && !ctype1.startsWith("text/xml")) {
        throw new IllegalStateException("unexpected content-type (part1)");
      }
      InputStream in1 = partInput1.getInputStream();
      String transformedXml = removeUsernameToken(in1);
      msgCtxt.setVariable(varName("transformed"), transformedXml);

      PartOutput partOutput1 = mpo.newPart();
      for (String headerName : partInput1.getHeaderNames()) {
        partOutput1.setHeaderField(headerName, partInput1.getHeaderField(headerName));
      }
      partOutput1
        .getOutputStream()
        .write(transformedXml.getBytes(StandardCharsets.UTF_8));

      // 2. extract the zip attachment here
      PartInput partInput2 = mpi.nextPart();
      String ctype2 = partInput2.getContentType();
      if (ctype2 == null) {
        throw new IllegalStateException("no content-type found (part2)");
      }
      if (!ctype2.startsWith("application/zip")) {
        throw new IllegalStateException("unexpected content-type (part2)");
      }
      PartOutput partOutput2 = mpo.newPart();
      for (String headerName : partInput2.getHeaderNames()) {
        partOutput2.setHeaderField(headerName, partInput2.getHeaderField(headerName));
      }
      ByteStreams.copy(partInput2.getInputStream(),
                       partOutput2.getOutputStream());

      // 3. concatenate the result and replace
      mpo.close();
      message.setContent(
          new ByteArrayInputStream(out.toByteArray()));

      return ExecutionResult.SUCCESS;
    } catch (IllegalStateException exc1) {
      String stacktrace = getStackTraceAsString(exc1);
      System.out.printf(stacktrace);
      setExceptionVariables(exc1, msgCtxt);
      return ExecutionResult.ABORT;
    } catch (Exception e) {
      if (getDebug()) {
        String stacktrace = getStackTraceAsString(e);
        System.out.printf(stacktrace);
        msgCtxt.setVariable(varName("stacktrace"), stacktrace);
      }
      setExceptionVariables(e, msgCtxt);
      return ExecutionResult.ABORT;
    }
  }
}
