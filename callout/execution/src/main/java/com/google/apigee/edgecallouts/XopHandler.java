// XopHandler.java
//
// Copyright (c) 2018-2021 Google LLC.
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
import com.google.apigee.IOUtil;
import com.google.apigee.xml.XPathEvaluator;
import com.google.apigee.xml.XmlUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import javax.xml.xpath.XPathConstants;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class XopHandler extends CalloutBase implements Execution {
  private static final String varprefix = "xop_";
  private static final boolean wantStringDefault = true;
  private static final XopAction DEFAULT_ACTION = XopAction.EDIT_1;
  private static final Base64.Encoder b64Encoder = Base64.getEncoder();

  public XopHandler(Map properties) {
    super(properties);
  }

  enum XopAction {
    EDIT_1,
    EXTRACT_SOAP,
    TRANSFORM_TO_EMBEDDED,
    UNSPECIFIED;

    public static XopAction findByName(String name) {
      for (XopAction action : XopAction.values()) {
        if (name.equals(action.name())) {
          return action;
        }
      }
      return XopAction.UNSPECIFIED;
    }
  };

  private XopAction getAction(MessageContext msgCtxt) throws Exception {
    String action = this.properties.get("action");
    if (action != null) action = action.trim();
    if (action == null || action.equals("")) {
      return DEFAULT_ACTION;
    }
    action = resolveVariableReferences(action, msgCtxt);

    XopAction xopAction = XopAction.findByName(action.toUpperCase());
    if (xopAction == XopAction.UNSPECIFIED)
      throw new IllegalStateException("specify a valid action.");

    return xopAction;
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

  private static boolean acceptableAttachmentContentType(String ctype) {
    return ctype.startsWith("application/zip")
            || ctype.startsWith("application/octet-stream")
      || ctype.startsWith("application/pdf");
  }

  // xmlns:xop='http://www.w3.org/2004/08/xop/include'
  // <xop:Include href="cid:uuid-here"/>
  private static String embedAttachment(Document document, InputStream binaryIn) throws Exception {
    XPathEvaluator xpe = new XPathEvaluator();
    xpe.registerNamespace(
        "xop",
        "http://www.w3.org/2004/08/xop/include");
    String xpath = "//xop:Include";
    NodeList nodes = (NodeList) xpe.evaluate(xpath, document, XPathConstants.NODESET);
    if (nodes.getLength() == 0) {
          throw new IllegalStateException(
              "could not find xop:Include element in the XML document");
    }
    if (nodes.getLength() != 1) {
          throw new IllegalStateException(
              "found more than one xop:Include element in the XML document");
    }

    // replace the Include element with the referenced text (base64 encoded)
    Node targetNode = nodes.item(0);
    Node newNode = document.createTextNode(b64Encoder.encodeToString(IOUtil.readAllBytes(binaryIn)));
    targetNode.getParentNode().replaceChild(newNode, targetNode);

    // xsi:type="base64binary"
    // Attr attr = document.createAttribute(parts[0]);
    // attr.setValue(parts[1]);

    return XmlUtils.toString(document, true);
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
      MultipartInput mpi = new MultipartInput(message.getContentAsStream(), originalContentType);

      Map<String, String> params = MultipartInput.parseParams(originalContentType);
      if (params.get("boundary") == null) {
        throw new IllegalStateException("no boundary found");
      }

      XopAction calloutAction = getAction(msgCtxt);
      msgCtxt.setVariable(varName("action"), calloutAction.name().toLowerCase());

      if (calloutAction == XopAction.EDIT_1) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MultipartOutput mpo = new MultipartOutput(out, originalContentType, params.get("boundary"));

        // 1. extract and transform the XML here
        PartInput partInput1 = mpi.nextPart();
        String ctype1 = partInput1.getContentType();
        if (ctype1 == null) {
          throw new IllegalStateException("no content-type found (part1)");
        }
        if (!ctype1.startsWith("application/soap+xml") && !ctype1.startsWith("text/xml")) {
          throw new IllegalStateException(
              String.format("unexpected content-type for part #1 (%s)", ctype1));
        }
        InputStream in1 = partInput1.getInputStream();
        String transformedXml = removeUsernameToken(in1);
        msgCtxt.setVariable(varName("transformed"), transformedXml);

        PartOutput partOutput1 = mpo.newPart();
        for (String headerName : partInput1.getHeaderNames()) {
          partOutput1.setHeaderField(headerName, partInput1.getHeaderField(headerName));
        }
        partOutput1.getOutputStream().write(transformedXml.getBytes(StandardCharsets.UTF_8));

        // 2. extract the attachment here
        PartInput partInput2 = mpi.nextPart();
        String ctype2 = partInput2.getContentType();
        if (ctype2 == null) {
          throw new IllegalStateException("no content-type found (part2)");
        }
        if (!acceptableAttachmentContentType(ctype2)) {
          throw new IllegalStateException(
              String.format("unexpected content-type for part #2 (%s)", ctype2));
        }
        PartOutput partOutput2 = mpo.newPart();
        for (String headerName : partInput2.getHeaderNames()) {
          partOutput2.setHeaderField(headerName, partInput2.getHeaderField(headerName));
        }
        IOUtil.copy(partInput2.getInputStream(), partOutput2.getOutputStream());

        // 3. concatenate the result and replace
        mpo.close();
        message.setContent(new ByteArrayInputStream(out.toByteArray()));

        return ExecutionResult.SUCCESS;
      }

      if (calloutAction == XopAction.TRANSFORM_TO_EMBEDDED) {
        // 1. get the Document for the XML here
        PartInput partInput1 = mpi.nextPart();
        String ctype1 = partInput1.getContentType();
        if (ctype1 == null) {
          throw new IllegalStateException("no content-type found (part1)");
        }
        if (!ctype1.startsWith("application/soap+xml") && !ctype1.startsWith("text/xml")) {
          throw new IllegalStateException(
              String.format("unexpected content-type for part #1 (%s)", ctype1));
        }
        Document document = XmlUtils.parseXml(partInput1.getInputStream());

        // 2. get the InputStream for the the attachment here
        PartInput partInput2 = mpi.nextPart();
        String ctype2 = partInput2.getContentType();
        if (ctype2 == null) {
          throw new IllegalStateException("no content-type found (part2)");
        }
        if (!acceptableAttachmentContentType(ctype2)) {
          throw new IllegalStateException(
              String.format("unexpected content-type for part #2 (%s)", ctype2));
        }

        // 3. embed the encoded attachment into the XML
        String resultXml = embedAttachment(document,
                                           partInput2.getInputStream());

        // 4. set the result as the response stream
        message.setContent(new ByteArrayInputStream(resultXml.getBytes()));
        message.setHeader("content-type", "text/xml");

        return ExecutionResult.SUCCESS;
      }

      if (calloutAction == XopAction.EXTRACT_SOAP) {
        // 1. extract the XML here
        PartInput partInput1 = mpi.nextPart();
        String ctype1 = partInput1.getContentType();
        if (ctype1 == null) {
          throw new IllegalStateException("no content-type found (part1)");
        }
        if (!ctype1.startsWith("application/soap+xml") && !ctype1.startsWith("text/xml")) {
          throw new IllegalStateException(
              String.format("unexpected content-type for part #1 (%s)", ctype1));
        }
        InputStream in1 = partInput1.getInputStream();
        msgCtxt.setVariable(
            varName("extracted_xml"), new String(IOUtil.readAllBytes(in1), StandardCharsets.UTF_8));
        return ExecutionResult.SUCCESS;
      }

      throw new IllegalStateException("unsupported action");

    } catch (IllegalStateException exc1) {
      setExceptionVariables(exc1, msgCtxt);
      return ExecutionResult.ABORT;
    } catch (Exception e) {
      if (getDebug()) {
        String stacktrace = getStackTraceAsString(e);
        msgCtxt.setVariable(varName("stacktrace"), stacktrace);
      }
      setExceptionVariables(e, msgCtxt);
      return ExecutionResult.ABORT;
    }
  }
}
