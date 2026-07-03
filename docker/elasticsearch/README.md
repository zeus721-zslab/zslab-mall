# Elasticsearch 오브젝트 수동 등록 절차

서버 ES(`zslab_elasticsearch`)는 로컬과 독립된 인스턴스이므로 배포 시 1회 수동 등록이 필요합니다.
data stream은 첫 로그 유입 시 template 기준 자동 생성되므로 수동 생성 불요.

## 등록 순서

ILM policy → index template 순서로 등록합니다.

### 1. ILM policy 등록

```bash
docker run --rm --network <ES_NETWORK> curlimages/curl -s -X PUT \
  "http://${ES_HOST}:${ES_PORT}/_ilm/policy/zslab-mall-logs-policy" \
  -H "Content-Type: application/json" \
  -d @/path/to/ilm-policy.json
```

또는 인라인으로:

```bash
docker run --rm --network <ES_NETWORK> curlimages/curl -s -X PUT \
  "http://${ES_HOST}:${ES_PORT}/_ilm/policy/zslab-mall-logs-policy" \
  -H "Content-Type: application/json" \
  --data-binary @docker/elasticsearch/ilm-policy.json
```

기대 응답: `{"acknowledged":true}`

### 2. index template 등록

```bash
docker run --rm --network <ES_NETWORK> curlimages/curl -s -X PUT \
  "http://${ES_HOST}:${ES_PORT}/_index_template/zslab-mall-logs" \
  -H "Content-Type: application/json" \
  --data-binary @docker/elasticsearch/index-template.json
```

기대 응답: `{"acknowledged":true}`

### 3. 등록 확인

```bash
# ILM policy 확인
docker run --rm --network <ES_NETWORK> curlimages/curl -s \
  "http://${ES_HOST}:${ES_PORT}/_ilm/policy/zslab-mall-logs-policy"

# index template 확인
docker run --rm --network <ES_NETWORK> curlimages/curl -s \
  "http://${ES_HOST}:${ES_PORT}/_index_template/zslab-mall-logs"

# Filebeat 기동 후 data stream 자동 생성 확인
docker run --rm --network <ES_NETWORK> curlimages/curl -s \
  "http://${ES_HOST}:${ES_PORT}/_data_stream/zslab-mall-logs"
```

## 변수 참조

| 변수 | 설명 | 기본값(로컬) |
|---|---|---|
| `ES_HOST` | Elasticsearch 컨테이너 이름 | `zslab_elasticsearch` |
| `ES_PORT` | Elasticsearch 포트 | `9200` |
| `ES_NETWORK` | Docker 네트워크 이름 | `zslab_zslab_net` |

## 정책 요약

- **ILM policy** `zslab-mall-logs-policy`: hot rollover(7일 / 10GB) → delete(rollover 후 30일)
- **index template** `zslab-mall-logs`: `zslab-mall-logs*` 패턴·data stream·priority 200·replica 0
