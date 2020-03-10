// Copyright 2017-2018 Google LLC.
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
package com.google.apigee.xml;

import java.io.CharArrayReader;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Iterator;
import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathConstants;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import java.util.Hashtable;

/**
 *
 * @author Michael Bien
 */
public class XPathEvaluator {

    private Transformer transformer;
    private DocumentBuilder docBuilder;

    public XPathEvaluator(){
        try {
            transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        } catch (TransformerConfigurationException ex) {
            ex.printStackTrace();
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            docBuilder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException ex) {
            ex.printStackTrace();
        }
    }

    private Hashtable<String, String> prefixi = new Hashtable<String, String> ();

    public void registerNamespace(String prefix, String ns) {
        prefixi.put(prefix, ns);
    }

    public String evalXPathToString(String xpath, Document doc) throws SAXException,IOException,TransformerException,XPathExpressionException {
        NodeList resultXML = (NodeList)evaluate(xpath, doc, XPathConstants.NODESET);
        return buildResult(resultXML);
    }

    public String evalXPathToString(String xpath, String xml) throws SAXException, IOException, TransformerException, XPathExpressionException {
        NodeList resultXML = (NodeList)evaluate(xpath, xml, XPathConstants.NODESET);
        return buildResult(resultXML);
    }

    private String buildResult(NodeList resultXML) throws TransformerException {

        if (resultXML.getLength() != 0) {

            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < resultXML.getLength(); i++) {

                Node item = resultXML.item(i);
                String nodeValue = item.getNodeValue();

                if (nodeValue == null) {
                    StringWriter stringWriter = new StringWriter();
                    transformer.transform(new DOMSource(item), new StreamResult(stringWriter));
                    sb.append(stringWriter.toString());
                } else {
                    sb.append(item.getNodeValue()).append("\n");
                }
            }
            return sb.toString();
        }
        else {
            return "";
        }
    }

    public Object evaluate(String xpath, String xml, QName ret)
        throws SAXException, IOException, XPathExpressionException {

        Document sourceXML = docBuilder.parse(new InputSource(new CharArrayReader(xml.toCharArray())));

        //hack; found no way to get it working with default namespaces
        if(sourceXML.lookupNamespaceURI(null) != null) {
            try {
                DocumentBuilderFactory fac = DocumentBuilderFactory.newInstance();
                fac.setNamespaceAware(false);
                sourceXML = fac.newDocumentBuilder().parse(new InputSource(new CharArrayReader(xml.toCharArray())));
            } catch (ParserConfigurationException ex) {}
        }

        // String uri = sourceXML.lookupNamespaceURI(null);
        // if (uri !=null) {
        //     registerNamespace("", uri);
        // }
        return evaluate(xpath, sourceXML, ret);
    }

    public Object evaluate(String xpath, Document sourceXML, QName ret)
        throws SAXException, IOException, XPathExpressionException {
        XPathFactory factory = XPathFactory.newInstance();
        XPath xPath = factory.newXPath();
        xPath.setNamespaceContext(new CustomNamespaceResolver(prefixi));
        XPathExpression expr = xPath.compile(xpath);
        return expr.evaluate(sourceXML, ret);
    }


    private final static class CustomNamespaceResolver implements NamespaceContext {
        private Hashtable<String,String> prefixes;
        public CustomNamespaceResolver(Hashtable<String,String> prefixi) {
            prefixes = prefixi;
        }

        public String getNamespaceURI(String prefix) {
            String ns = prefixes.get(prefix);
            return ns;
        }

        public String getPrefix(String namespaceURI) {
            throw new UnsupportedOperationException();
        }

        public Iterator getPrefixes(String namespaceURI) {
            throw new UnsupportedOperationException();
        }
    }

}
