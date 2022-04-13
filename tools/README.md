## Tool for exercising the example proxy

The script contained in this directory will:
- create a multipart/related HTTP message with soap+xml and a xop:Include
- embed a zip file or a pdf file as the 2nd part of the document
- invoke the xop-handler endpoint with that multipart/related message

This replicates what you can do with SOAP-UI as far as attachments.

You need to have deployed the example proxy bundle in order to use this tool.

### Usage:

To run all test cases:
```
endpoint=https://my-apigee-endpoint
./create-and-send-soap-mtom-xop-request.sh $endpoint
```

To run all one test case:
```
endpoint=https://my-apigee-endpoint
./create-and-send-soap-mtom-xop-request.sh $endpoint 3
```

