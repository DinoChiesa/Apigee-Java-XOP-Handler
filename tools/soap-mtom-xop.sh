#!/bin/bash

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

TARGET_URL_BASE="${ENDPOINT}/xop-handler"
BOUNDARY=$(pseudo_uuid)
PART1_CONTENT_ID=$(short_random_string 12)
PART2_CONTENT_ID=$(pseudo_uuid)
scriptdir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
a=( ${scriptdir}/*.zip )
# Select a random zip file
ATTACHMENT_FILENAME=( "${a[RANDOM%${#a[@]}]"{1..42}"}" )
HREF_ID=$(pseudo_uuid)

if [[ ! -f "$ATTACHMENT_FILENAME" ]]; then
    printf "cannot find that file.\n"
    exit 1
fi

BASE_FILENAME=$(basename ${ATTACHMENT_FILENAME})

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
REQUEST_BODY=$(mktemp)

echo Using temporary file: ${REQUEST_BODY}
echo

# Stitch the request body together by concatenating all parts in the right order.
echo "$PART1_HEADERS" >> ${REQUEST_BODY}
# We use ANSI-C quoting for enforcing newlines: https://stackoverflow.com/a/5295906/1523342
echo $'\r\n\r\n'            >> ${REQUEST_BODY}
echo "$XML_REQUEST"         >> ${REQUEST_BODY}
echo $'\r\n'                >> ${REQUEST_BODY}
echo "$PART2_HEADERS"       >> ${REQUEST_BODY}
echo $'\r\n\r\n'            >> ${REQUEST_BODY}
cat ${ATTACHMENT_FILENAME}  >> ${REQUEST_BODY}
echo $MIME_TERMINATOR       >> ${REQUEST_BODY}

# Finally, upload the request body
# Based on & inspired by: https://stackoverflow.com/a/45289969/1523342
for (( N=1; N < 5; ++N ))
do
  URL=${TARGET_URL_BASE}/t${N}
  printf "\n********\n******** Testcase $N\n${URL}\n"
  read -p "ENTER to continue... " dummy

  curl -i ${URL} \
      -H "Content-Type: multipart/related; start=\"${PART1_CONTENT_ID}\"; boundary=\"${BOUNDARY}\"" \
      -H "MIME-Version: 1.0" \
      -H "SOAPAction: ${SOAPAction}" \
      --data-binary @${REQUEST_BODY} \

done

# Remove the temporary file.
rm ${REQUEST_BODY}
