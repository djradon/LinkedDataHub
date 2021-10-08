#!/bin/bash

initialize_dataset "$END_USER_BASE_URL" "$TMP_END_USER_DATASET" "$END_USER_ENDPOINT_URL"
initialize_dataset "$ADMIN_BASE_URL" "$TMP_ADMIN_DATASET" "$ADMIN_ENDPOINT_URL"
purge_backend_cache "$END_USER_VARNISH_SERVICE"
purge_backend_cache "$ADMIN_VARNISH_SERVICE"

# access is unauthorized

curl -k -w "%{http_code}\n" -f -s \
  -E "${AGENT_CERT_FILE}":"${AGENT_CERT_PWD}" \
  -H "Accept: application/n-triples" \
  -X DELETE \
  "${END_USER_BASE_URL}" \
| grep -q "${STATUS_FORBIDDEN}"

pushd . > /dev/null && cd "$SCRIPT_ROOT/admin/acl"

# create group

group_doc=$(./create-group.sh \
  -f "$OWNER_CERT_FILE" \
  -p "$OWNER_CERT_PWD" \
  -b "$ADMIN_BASE_URL" \
  --name "Test group" \
  --member "$AGENT_URI")

echo "GROUP_DOC: $group_doc"

group=$(curl -s -k \
  -E "${OWNER_CERT_FILE}":"${OWNER_CERT_PWD}" \
  "$group_doc" \
  -H "Accept: application/n-triples" \
  | cat \
  | sed -rn "s/<(.*)> <http:\/\/xmlns.com\/foaf\/0.1\/isPrimaryTopicOf> <${group_doc//\//\\/}> \./\1/p")

echo "GROUP: $group"

# create authorization

./create-authorization.sh \
  -f "$OWNER_CERT_FILE" \
  -p "$OWNER_CERT_PWD" \
  -b "$ADMIN_BASE_URL" \
  --label "DELETE authorization" \
  --agent-group "$group" \
  --to "$END_USER_BASE_URL" \
  --write

popd > /dev/null

# access is allowed after authorization is created

curl -k -w "%{http_code}\n" -f -s \
  -E "${AGENT_CERT_FILE}":"${AGENT_CERT_PWD}" \
  -H "Accept: application/n-triples" \
  -X DELETE \
  "${END_USER_BASE_URL}" \
| grep -q "${STATUS_NO_CONTENT}"