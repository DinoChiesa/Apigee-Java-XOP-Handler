#!/bin/bash
#
# create-and-send-soap-mtom-xop-request.sh
#
# This tool creates a multipart/related message (See IETF RFC 2387) that
# includes a SOAP message as the first part, and a binary stream as the second
# part. The SOAP message uses MTOM and XOP to refer to the second part. The tool
# then sends that message via curl to a specific endpoint.
#
# usage:
#   endpoint=https://my-apigee-endpoint.com
#   create-and-send-soap-mtom-xop-request.sh $endpoint
#
# The tool works by randomly selecting a PDF or ZIP file from the current
# directory, and using that for the content for the Included data. The SOAP
# message always uses Content-Type: application/soap+xml; charset=UTF-8, and the
# binary data always uses Content-Type: application/octet-stream.
#
# This tool assumes the example API proxy bundle for demonstrating the XOP
# handler is already deployed and available at $endpoint/xop-handler .
#
# It sends the same message to the proxy 4 times, once for each
# conditional flow. This allows you to exercise all the paths into the proxy,
# which then uses different configurations for the xop-handler callout.
#
# If you want to exercise only a specific flow, for example #3 which performs
# the transform_to_embedded action, then append that number to the command line:
#
#   create-and-send-soap-mtom-xop-request.sh $endpoint 3
#


short_random_string() {
    printf $(hexdump -n $1 -e '8/8 "%08X"' /dev/random  | tr -d '[:space:]')
}

pseudo_uuid() {
    local N B T

    for (( N=0; N < 16; ++N ))
    do
        B=$(( $RANDOM%255 ))

        if (( N == 6 ))
        then
            printf '4%x' $(( B%15 ))
        elif (( N == 8 ))
        then
            local C='89ab'
            printf '%c%x' ${C:$(( $RANDOM%${#C} )):1} $(( B%15 ))
        else
            printf '%02x' $B
        fi

        for T in 3 5 7 9
        do
            if (( T == N ))
            then
                printf '-'
                break
            fi
        done
    done

    echo
}

ENDPOINT=$1
if [[ "$ENDPOINT" == "" ]]; then
    printf "You must pass an endpoint.\n"
    exit 1
fi

if [[ ! "$ENDPOINT" == http* ]]; then
    printf "You must pass an http endpoint.\n"
    exit 1
fi

TESTCASE=$2


TARGET_URL_BASE="${ENDPOINT}/xop-handler"
BOUNDARY=$(pseudo_uuid)
PART1_CONTENT_ID=$(short_random_string 12)
PART2_CONTENT_ID=$(pseudo_uuid)
scriptdir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
a=( ${scriptdir}/*.zip ${scriptdir}/*.pdf )
# a=( ${scriptdir}/*.pdf )
# Select a random file
ATTACHMENT_FILENAME=( "${a[RANDOM%${#a[@]}]"{1..42}"}" )
HREF_ID=$(pseudo_uuid)

if [[ ! -f "$ATTACHMENT_FILENAME" ]]; then
    printf "cannot find the randomly-selected file.\n"
    exit 1
fi

BASE_FILENAME=$(basename ${ATTACHMENT_FILENAME})

printf "Selected content file: %s\n" ${BASE_FILENAME}


# Prepare the headers for the XML request body
read -r -d '' PART1_HEADERS << EOM
--$BOUNDARY
Content-Type: application/soap+xml; charset=UTF-8
Content-ID: ${PART1_CONTENT_ID}
EOM

# Prepare the XML request body itself
read -r -d '' XML_REQUEST << EOM
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
           <Field name='dDocTitle'>${BASE_FILENAME}</Field>
           <File name='primaryFile' href='${HREF_ID}'>
             <Contents><Include xmlns='http://www.w3.org/2004/08/xop/include' href='cid:${PART2_CONTENT_ID}'/></Contents>
           </File>
         </Document>
       </Service>
     </GenericRequest>
   </S:Body>
</S:Envelope>
EOM

# Prepare the headers for the attachment
read -r -d '' PART2_HEADERS << EOM
--$BOUNDARY
Content-Type: application/octet-stream
Content-Transfer-Encoding: binary
Content-ID: <${PART2_CONTENT_ID}>
Content-Disposition: attachment; name="${BASE_FILENAME}"; filename="${BASE_FILENAME}"
EOM

# Prepare the terminating line
read -r -d '' MIME_TERMINATOR << EOM
--${BOUNDARY}--
EOM

# Create a temporary file
REQUEST_BODY_FILE=$(mktemp)

printf "Using temporary file: %s\n" ${REQUEST_BODY_FILE}

# Stitch the request body together by concatenating all parts in the right order.
printf -- "$PART1_HEADERS"      >> ${REQUEST_BODY_FILE}
# We use ANSI-C quoting for enforcing newlines: https://stackoverflow.com/a/5295906/1523342
printf $'\n\n'               >> ${REQUEST_BODY_FILE}
printf "$XML_REQUEST"        >> ${REQUEST_BODY_FILE}
printf $'\n\n'               >> ${REQUEST_BODY_FILE}
printf -- "$PART2_HEADERS"   >> ${REQUEST_BODY_FILE}
printf $'\n\n'               >> ${REQUEST_BODY_FILE}
cat ${ATTACHMENT_FILENAME}   >> ${REQUEST_BODY_FILE}
printf $'\n'                 >> ${REQUEST_BODY_FILE}
printf -- "$MIME_TERMINATOR" >> ${REQUEST_BODY_FILE}
printf $'\n'                 >> ${REQUEST_BODY_FILE}

# Finally, upload the request body
send_one() {
    local N=$1
    local dummy
    local URL=${TARGET_URL_BASE}/t${N}
    printf "********\n******** Testcase $N\n${URL}\n"
    read -p "ENTER to continue... " dummy

    curl -i ${URL} \
          -H "Content-Type: multipart/related; start=\"${PART1_CONTENT_ID}\"; boundary=\"${BOUNDARY}\"" \
          -H "MIME-Version: 1.0" \
          -H "SOAPAction: ${SOAPAction}" \
          --data-binary @${REQUEST_BODY_FILE}
}

if [[ "$TESTCASE" == "" ]]; then
  for (( N=1; N < 5; ++N ))
  do
    send_one $N
  done
else
  send_one ${TESTCASE}
fi

# Remove the temporary file.
rm ${REQUEST_BODY_FILE}
