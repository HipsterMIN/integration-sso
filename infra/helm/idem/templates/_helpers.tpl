{{/*
Idem Helm 차트 — 공통 헬퍼 (S9 PR-3)
*/}}

{{- define "idem.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "idem.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* 공통 레이블 */}}
{{- define "idem.labels" -}}
helm.sh/chart: {{ include "idem.chart" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/part-of: idem
idem.io/edition: {{ .Values.global.edition }}
environment: {{ .Values.global.env }}
{{- end }}

{{/* 컴포넌트 selector 레이블 — 인자: 컴포넌트 이름(문자열) */}}
{{- define "idem.selectorLabels" -}}
app: {{ . }}
app.kubernetes.io/component: {{ . }}
{{- end }}

{{/*
이미지 레퍼런스 — 인자: dict "root" $ "image" .Values.x.image "edition" (bool)
  edition=true 인 컴포넌트(hub·registry)는 <tag>-<global.edition> 을 쓴다 (compose.install.yml 과 같은 규칙)
  1.0.1 (3차 점검 H5): global.imageRegistry 는 Idem 이미지(레지스트리 없는 짧은 이름)에만 붙는다. image.registry 키가 있으면 그 값이 우선한다
  (빈 문자열 = 접두 없음) — keycloak·dbInit 같은 서드파티 이미지는 values 가 registry: "" 를 준다.
*/}}
{{- define "idem.image" -}}
{{- $root := .root -}}
{{- $tag := .image.tag | default $root.Values.global.imageTag -}}
{{- if .edition }}{{ $tag = printf "%s-%s" $tag $root.Values.global.edition }}{{ end -}}
{{- $registry := $root.Values.global.imageRegistry -}}
{{- if hasKey .image "registry" }}{{ $registry = .image.registry }}{{ end -}}
{{- if and $registry (not (contains "/" .image.repository)) -}}
{{ printf "%s/%s:%s" $registry .image.repository $tag }}
{{- else -}}
{{ printf "%s:%s" .image.repository $tag }}
{{- end -}}
{{- end }}

{{/* Keycloak 내부 URL — 차트가 올리면 클러스터 안 서비스, 아니면 externalUrl */}}
{{- define "idem.keycloakUrl" -}}
{{- if .Values.keycloak.enabled -}}
http://idem-keycloak:8080
{{- else -}}
{{ required "keycloak.enabled=false 면 keycloak.externalUrl 이 필요합니다" .Values.keycloak.externalUrl }}
{{- end -}}
{{- end }}

{{/* Secret 참조 env 항목 — 인자: dict "name" ENV "secret" SecretName "key" Key */}}
{{- define "idem.secretEnv" -}}
- name: {{ .name }}
  valueFrom:
    secretKeyRef:
      name: {{ .secret }}
      key: {{ .key }}
{{- end }}

{{/* 앱 비밀 한 벌에서 같은 이름으로 — 인자: dict "root" $ "keys" (list) */}}
{{- define "idem.appSecretEnv" -}}
{{- $root := .root -}}
{{- range .keys }}
{{ include "idem.secretEnv" (dict "name" . "secret" $root.Values.secrets.existingSecret "key" .) }}
{{- end }}
{{- end }}

{{/* 앱 공통 env (compose x-app-common) — 인자: $ */}}
{{- define "idem.commonEnv" -}}
- name: DB_HOST
  value: {{ .Values.infra.postgres.host | quote }}
- name: DB_PORT
  value: {{ .Values.infra.postgres.port | quote }}
- name: DB_NAME
  value: {{ .Values.infra.postgres.database | quote }}
{{ include "idem.secretEnv" (dict "name" "DB_USERNAME" "secret" .Values.infra.postgres.existingSecret "key" .Values.infra.postgres.usernameKey) }}
{{ include "idem.secretEnv" (dict "name" "DB_PASSWORD" "secret" .Values.infra.postgres.existingSecret "key" .Values.infra.postgres.passwordKey) }}
- name: REDIS_HOST
  value: {{ .Values.infra.redis.host | quote }}
- name: REDIS_PORT
  value: {{ .Values.infra.redis.port | quote }}
{{- if .Values.infra.redis.existingSecret }}
{{ include "idem.secretEnv" (dict "name" "REDIS_PASSWORD" "secret" .Values.infra.redis.existingSecret "key" .Values.infra.redis.passwordKey) }}
{{- end }}
- name: IDEM_KAFKA_ENABLED
  value: {{ .Values.infra.kafka.enabled | quote }}
{{- if .Values.infra.kafka.enabled }}
- name: KAFKA_SERVERS
  value: {{ required "infra.kafka.enabled=true 면 bootstrapServers 가 필요합니다" .Values.infra.kafka.bootstrapServers | quote }}
{{- end }}
- name: TZ
  value: {{ .Values.global.timezone | quote }}
- name: DB_SSLMODE
  value: {{ .Values.infra.postgres.sslMode | quote }}
# 1.0.1 (3차 점검 M6·M7): 운영 프로파일 + actuator 를 별도 관리 포트로 (Service·Ingress 는 http 포트만 내보낸다)
- name: SPRING_PROFILES_ACTIVE
  value: {{ .Values.appDefaults.springProfile | quote }}
- name: IDEM_MANAGEMENT_PORT
  value: {{ .Values.appDefaults.managementPort | quote }}
{{- end }}

{{/* map → env 항목 — 인자: map */}}
{{- define "idem.mapEnv" -}}
{{- range $k, $v := . }}
- name: {{ $k }}
  value: {{ $v | quote }}
{{- end }}
{{- end }}

{{/* Spring Boot 앱 프로브 — 인자: dict "root" $ "port" N. actuator 는 관리 포트(appDefaults.managementPort)에 있다 — .port 는 그 값이 비어 있을 때만 */}}
{{- define "idem.probes" -}}
{{- $d := .root.Values.appDefaults -}}
{{- $port := $d.managementPort | default .port -}}
startupProbe:
  httpGet: { path: /actuator/health/liveness, port: {{ $port }} }
  failureThreshold: {{ $d.startupProbe.failureThreshold }}
  periodSeconds: {{ $d.startupProbe.periodSeconds }}
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: {{ $port }} }
  periodSeconds: {{ $d.livenessProbe.periodSeconds }}
  failureThreshold: {{ $d.livenessProbe.failureThreshold }}
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: {{ $port }} }
  periodSeconds: {{ $d.readinessProbe.periodSeconds }}
  failureThreshold: {{ $d.readinessProbe.failureThreshold }}
{{- end }}

{{/* Spring Boot 앱 컨테이너 포트 — 인자: dict "root" $ "port" N (http + 관리 포트) */}}
{{- define "idem.appPorts" -}}
ports:
  - { name: http, containerPort: {{ .port }} }
  {{- if .root.Values.appDefaults.managementPort }}
  - { name: management, containerPort: {{ .root.Values.appDefaults.managementPort }} }
  {{- end }}
{{- end }}

{{/* Pod 공통 spec 조각 — 인자: dict "root" $ "name" 컴포넌트 ["uid" N]. 1.0.1 (3차 점검 H4): runAsNonRoot 는 숫자 UID 가 있어야 kubelet 이 통과시킨다 */}}
{{- define "idem.podCommon" -}}
{{- $uid := .uid | default .root.Values.appDefaults.runAsUser -}}
{{- with .root.Values.global.imagePullSecrets }}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end }}
terminationGracePeriodSeconds: {{ .root.Values.appDefaults.terminationGracePeriodSeconds }}
securityContext:
  runAsNonRoot: true
  runAsUser: {{ $uid }}
  runAsGroup: {{ $uid }}
  fsGroup: {{ $uid }}
  seccompProfile: { type: RuntimeDefault }
{{- if ne .root.Values.appDefaults.podAntiAffinity "none" }}
affinity:
  podAntiAffinity:
    {{- if eq .root.Values.appDefaults.podAntiAffinity "hard" }}
    requiredDuringSchedulingIgnoredDuringExecution:
      - labelSelector: { matchLabels: { app: {{ .name }} } }
        topologyKey: kubernetes.io/hostname
    {{- else }}
    preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          labelSelector: { matchLabels: { app: {{ .name }} } }
          topologyKey: kubernetes.io/hostname
    {{- end }}
{{- end }}
{{- end }}

{{/* 컨테이너 보안 컨텍스트 */}}
{{- define "idem.containerSecurity" -}}
securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: false
  capabilities: { drop: ["ALL"] }
{{- end }}

{{/* URL 에서 host 만 — 인자: URL 문자열. 경로·포트가 있어도 호스트만 남긴다 (Ingress host 에는 포트를 쓸 수 없다) */}}
{{- define "idem.host" -}}
{{- . | trimPrefix "https://" | trimPrefix "http://" | splitList "/" | first | splitList ":" | first -}}
{{- end }}

{{/* 컴포넌트 replicaCount — 인자: dict "root" $ "c" 컴포넌트 values */}}
{{- define "idem.replicas" -}}
{{- if hasKey .c "replicaCount" }}{{ .c.replicaCount }}{{ else }}{{ .root.Values.appDefaults.replicaCount }}{{ end -}}
{{- end }}
