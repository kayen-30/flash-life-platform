ARG ELASTICSEARCH_VERSION=8.18.1
ARG ELASTICSEARCH_IMAGE=m.daocloud.io/docker.elastic.co/elasticsearch/elasticsearch
FROM ${ELASTICSEARCH_IMAGE}:${ELASTICSEARCH_VERSION}

ARG ELASTICSEARCH_VERSION
# IK 插件必须与 Elasticsearch 服务端版本严格一致。
RUN bin/elasticsearch-plugin install --batch \
    "https://get.infini.cloud/elasticsearch/analysis-ik/${ELASTICSEARCH_VERSION}"
