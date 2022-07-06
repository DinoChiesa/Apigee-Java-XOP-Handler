// XopHandler.java
//
// Copyright (c) 2018-2022 Google LLC.
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.xpath.XPathConstants;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class XopHandler extends CalloutBase implements Execution {
  private static final String varprefix = "xop_";
  private static final boolean wantStringDefault = true;
  private static final XopAction DEFAULT_ACTION = XopAction.EDIT_1;
  private static final Base64.Encoder b64Encoder = Base64.getEncoder();
  private static final List<String> DEFAULT_PART1_CTYPES =
      Arrays.asList("application/soap+xml", "application/xop+xml", "text/xml");
  private static final List<String> DEFAULT_PART2_CTYPES =
      Arrays.asList(
          "application/zip",
          "application/octet-stream",
          "image/jpeg",
          "image/png",
          "application/pdf");

  private static final Pattern contentIdPattern = Pattern.compile("^.*<([^>]+)>$");

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

  private static String unquote(String s) {
    int L = s.length();
    if (L >= 2 && s.charAt(0) == '"' && s.charAt(L - 1) == '"') {
      s = s.substring(1, L - 1);
    }
    return s;
  }

  private List<String> getList(MessageContext msgCtxt, String property, List<String> defaultValue) {
    String ctypes = this.properties.get(property);
    if (ctypes != null) ctypes = ctypes.trim();
    if (ctypes == null || ctypes.equals("")) {
      return defaultValue;
    }
    ctypes = resolveVariableReferences(ctypes, msgCtxt);
    return Arrays.asList(ctypes.split("\\s*,\\s*")).stream()
        .map(XopHandler::unquote)
        .collect(Collectors.toList());
  }

  private List<String> getAcceptablePart1ContentTypes(MessageContext msgCtxt) {
    return getList(msgCtxt, "part1-ctypes", DEFAULT_PART1_CTYPES);
  }

  private List<String> getAcceptableAttachmentContentTypes(MessageContext msgCtxt) {
    return getList(msgCtxt, "part2-ctypes", DEFAULT_PART2_CTYPES);
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

  private static boolean acceptableCtype(List<String> acceptableList, String ctype) {
    return acceptableList.stream().anyMatch(s -> ctype.startsWith(s));
  }

  // private static boolean acceptableAttachmentContentType(String ctype) {
  //   return ctype.startsWith("application/zip")
  //       || ctype.startsWith("application/octet-stream")
  //       || ctype.startsWith("image/jpeg")
  //       || ctype.startsWith("image/png")
  //       || ctype.startsWith("application/pdf");
  // }
  //
  // private static boolean acceptablePart1ContentType(String ctype) {
  //   return ctype.startsWith("application/soap+xml")
  //       || ctype.startsWith("application/xop+xml")
  //       || ctype.startsWith("text/xml");
  // }

  // xmlns:xop='http://www.w3.org/2004/08/xop/include'
  // <xop:Include href="cid:uuid-here"/>

  private static String embedAttachments(
      Document document, MultipartInput mpi, List<String> acceptableAttachmentContentTypes)
      throws Exception {

    // prepare to get the list of xop:Include elements in the document
    final XPathEvaluator xpe = new XPathEvaluator();
    xpe.registerNamespace("xop", "http://www.w3.org/2004/08/xop/include");

    Function<String, Element> findIncludeElement =
        (contentId) -> {
          try {
            final String desiredHref = "cid:" + contentId;

            // find the matching element
            final String xpath = String.format("//xop:Include[@href='%s']", desiredHref);
            NodeList includes = (NodeList) xpe.evaluate(xpath, document, XPathConstants.NODESET);
            if (includes.getLength() == 0) {
              return null;
            }
            if (includes.getLength() != 1) {
              throw new IllegalStateException(
                  String.format(
                      "multiple matching xop:Include elements in the XML document (href='%s')",
                      desiredHref));
            }
            return (Element) includes.item(0);

          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        };

    // Match up the include elements with the streams for the attachment parts.
    // Traverse the attachment streams in order.
    int p = 1;
    for (PartInput attachmentPart; (attachmentPart = mpi.nextPart()) != null; ) {
      p++;
      // get the InputStream for the the attachment here
      String ctype = attachmentPart.getContentType();
      if (ctype == null) {
        throw new IllegalStateException(String.format("no content-type found for part #%d", p));
      }

      if (!acceptableCtype(acceptableAttachmentContentTypes, ctype)) {
        throw new IllegalStateException(
            String.format("unexpected content-type for part #%d (%s)", p, ctype));
      }

      final String partContentIdHeader = attachmentPart.getHeaderField("Content-ID");
      if (partContentIdHeader == null) {
        throw new IllegalStateException(String.format("missing Content-ID for part #%d", p));
      }

      // extract the string enclosed in angle brackets
      Matcher m = contentIdPattern.matcher(partContentIdHeader.trim());
      if (!m.matches()) {
        throw new IllegalStateException(String.format("malformed Content-ID for part #%d", p));
      }

      // find the unique matching xop:Include element for this part
      String contentId = m.group(1);
      Element includeElement = findIncludeElement.apply(contentId);
      if (includeElement == null) {
        // Now, re-try with url-encoded value.
        // There is no "encode-for-uri" function in xpath 1.0, so we need to evaluate twice.
        String urlEncodedContentId = URLEncoder.encode(contentId, StandardCharsets.UTF_8.name());
        includeElement = findIncludeElement.apply(urlEncodedContentId);
        if (includeElement == null) {
          throw new IllegalStateException(
              String.format(
                  "no matching xop:Include element in the XML document (href='cid:%s')",
                  contentId));
        }
      }

      Node parent = includeElement.getParentNode();
      NodeList children = parent.getChildNodes();

      // remove all child whitespace text nodes, and check that there are no child Elements
      for (int ix = children.getLength() - 1; ix >= 0; ix--) {
        Node child = children.item(ix);
        if (child.getNodeType() == Node.ELEMENT_NODE && !includeElement.equals(child)) {
          throw new IllegalStateException(
              "the xop:Include element is not the sole child of its parent");
        }
        parent.removeChild(child);
      }

      Node newNode =
          document.createTextNode(
              b64Encoder.encodeToString(IOUtil.readAllBytes(attachmentPart.getInputStream())));

      parent.appendChild(newNode);
    }
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

      List<String> acceptablePart1ContentTypes = getAcceptablePart1ContentTypes(msgCtxt);

      if (calloutAction == XopAction.EDIT_1) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MultipartOutput mpo = new MultipartOutput(out, originalContentType, params.get("boundary"));

        // 1. extract and transform the XML here
        PartInput partInput1 = mpi.nextPart();
        String ctype1 = partInput1.getContentType();
        if (ctype1 == null) {
          throw new IllegalStateException("no content-type found (part1)");
        }
        if (!acceptableCtype(acceptablePart1ContentTypes, ctype1)) {
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
        List<String> acceptableAttachmentContentTypes =
            getAcceptableAttachmentContentTypes(msgCtxt);
        if (!acceptableCtype(acceptableAttachmentContentTypes, ctype2)) {
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
        if (!acceptableCtype(acceptablePart1ContentTypes, ctype1)) {
          throw new IllegalStateException(
              String.format("unexpected content-type for part #1 (%s)", ctype1));
        }
        Document document = XmlUtils.parseXml(partInput1.getInputStream());

        // 2. embed the encoded attachments into the XML
        String resultXml =
            embedAttachments(document, mpi, getAcceptableAttachmentContentTypes(msgCtxt));

        // 3. set the result as the response stream
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
        if (!acceptableCtype(acceptablePart1ContentTypes, ctype1)) {
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
