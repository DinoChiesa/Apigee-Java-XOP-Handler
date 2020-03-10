# Apigee Edge XOP Editor

This directory contains the Java source code and pom.xml file required to build
a Java callout that reads a multipart/related payload with a
[XOP](https://www.w3.org/TR/xop10/#xop_include) message payload, parses the SOAP
portion to remove the UsernameToken, and then replaces the modified SOAP payload
in the message. The XOP attachment remains unchanged.

For parsing the multipart/related data, this callout relies on the BSD-licensed
code forked from [danieln](https://github.com/DanielN/multipart-handler/).

## Disclaimer

This example is not an official Google product, nor is it part of an official Google product.


## Using this policy

You do not need to build the source code in order to use the policy in
Apigee Edge. All you need is the built JAR, and the appropriate
configuration for the policy.

If you want to build it, the instructions are at the bottom of this readme.


1. copy the callout jar file, available in
   `target/edge-custom-xop-editor-\*.jar`, and its dependency
   `multipart-handler-\*.jar`, to your apiproxy/resources/java directory. You can
   do this offline, or using the graphical Proxy Editor in the Apigee
   Edge Admin Portal.

2. include an XML file for the Java callout policy in your
   `apiproxy/resources/policies` directory. It should look something like this:

   ```xml
   <JavaCallout name='Java-ProcessXop-1'>
     <Properties>
       <Property name="source">message</Property>
     </Properties>
     <ClassName>com.google.apigee.edgecallouts.XopEditor</ClassName>
     <ResourceURL>java://edge-custom-xop-editor-20200310-u1.jar</ResourceURL>
   </JavaCallout>
   ```

3. use the Edge UI, or a command-line tool like
   [importAndDeploy.js](https://github.com/DinoChiesa/apigee-edge-js-examples/blob/master/importAndDeploy.js) or
   [apigeetool](https://github.com/apigee/apigeetool-node)
   or similar, to import your proxy into an Edge organization, and then deploy the proxy .
   Eg, `./importAndDeploy.js -n -v -o ${ORG} -e ${ENV} -d bundle/`

4. Use a client to generate and send http requests to the proxy you just deployed . Eg,
   ```
   curl -i https://$ORG-$ENV.apigee.net/xop-editor/t1 -X POST -d @xopfile.bin
   ```


## Notes on Usage

This repo includes a single callout class, `com.google.apigee.edgecallouts.XopEditor`.

The inbound message should bear a set of headers that includes a `content-type`
which indicates "multipart/related":

```
Content-Type: Multipart/Related; boundary=MIME_boundary; start='<rootpart@soapui.org>'
```

...and the message payload should look like this:
```
--MIME_boundary
Content-Type: application/soap+xml; charset=UTF-8
Content-ID: <rootpart@soapui.org>

<S:Envelope xmlns:S='http://schemas.xmlsoap.org/soap/envelope/'>
  <S:Header>
    <wsse:Security
        xmlns:wsse='http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd'>
      <wsse:UsernameToken
          xmlns:wsse='http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd'>
        <wsse:Username
            xmlns:wsse='http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd'>XXXXXX</wsse:Username>
        <wsse:Password
            xmlns:wsse='http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd'>XXXXXX</wsse:Password>
      </wsse:UsernameToken>
    </wsse:Security>
  </S:Header>
  <S:Body>
    <GenericRequest
        xmlns='http://www.oracle.com/UCM' webKey='cs'>
      <Service IdcService='CHECKIN_UNIVERSAL'>
        <Document>
          <Field name='UserDateFormat'>iso8601</Field>
          <Field name='UserTimeZone'>UTC</Field>
          <Field name='dDocName'>201807111403445918-1-464</Field>
          <Field name='dSecurityGroup'>FAFusionImportExport</Field>
          <Field name='dDocAccount'>hcm$/dataloader$/import$</Field>
          <Field name='dDocType'>Application</Field>
          <Field name='dDocTitle'>201807111403445918_76_I228_1_ValueSet_Budget_Center_ID_Independent.zip</Field>
          <File name='primaryFile' href='201807111403445918_76_I228_1_ValueSet_Budget_Center_ID_Independent.zip'>
            <Contents>
              <Include
                  xmlns='http://www.w3.org/2004/08/xop/include' href='cid:0b83cd6b-af15-45d2-bbda-23895de2a73d'/>
            </Contents>
          </File>
        </Document>
      </Service>
    </GenericRequest>
  </S:Body>
</S:Envelope>

--MIME_boundary
Content-Type: application/zip
Content-Transfer-Encoding: binary
Content-ID: <0b83cd6b-af15-45d2-bbda-23895de2a73d>

...binary zip data...

--MIME_boundary--
```

The callout strips out the UsernameToken element and sets the modified SOAP
message, along with the unmodified XOP attachment into the the message.content
variable.


## Additional Notes

1. This callout uses a modified version of the multipart-handler module. The
   source of that version is included here.

   The modifications include:
   * mark one private static method as public on MultipartInput
   * expose one new method on PartInput: getHeaderNames()

2. The callout is fairly rigid. It handles only:
   * messages with 2 parts
   * the first part must have content-type: `application/soap+xml` or `text/xml`, and
     must be a valid SOAP message.
   * the second part must have content-type: application/zip

   You could use this as-is, _or_, use it as a starting point, if you wanted to
   do something different with a XOP message.

3. The multipart-handler module also allows you to construct XOP messages. So
   you could use the code here as an starting point to a callout that accepts an
   inbound binary stream, and then constructs a SOAP MTOM+XOP message. Lots of
   other possibilities, of course.


## Example API Proxy

You can find an example proxy bundle that uses the policy, [here in this
repo](bundle/apiproxy). The example proxy accepts a post.

You must deploy the proxy in order to invoke it.

Invoke it like this:

```
ORG=myorg
ENV=myenv
curl -i -X POST --data-binary @example.txt \
   -H "content-type: Multipart/Related; boundary=MIME_boundary; start='<rootpart@soapui.org>'" \
   https://$ORG-$ENV.apigee.net/xop-editor/t1
```


## Building

Building from source requires Java 1.8, and Maven.

1. unpack (if you can read this, you've already done that).

2. Before building _the first time_, configure the build on your machine by
   loading the Apigee jars into your local cache:

   ```
   ./buildsetup.sh
   ```

3. Build with maven.
   ```
   cd callout
   mvn clean package
   ```

   This will build the dependency jar, and the callout jar, and then also run all
   the tests. After successful tests, it will copy the jar to the resource
   directory in the sample apiproxy bundle.


## License

This material is Copyright 2018-2020 Google LLC.  and is licensed under the
[Apache 2.0 License](LICENSE). This includes the Java code as well as the API
Proxy configuration.

## Bugs

* The automated tests are pretty thin.
