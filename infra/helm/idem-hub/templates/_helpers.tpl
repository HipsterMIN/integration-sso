{{/*
IdO Helm Chart 헬퍼 템플릿
*/}}

{{/*
전체 이름 생성
*/}}
{{- define "ido.fullname" -}}
{{- printf "%s" .Release.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
공통 레이블
*/}}
{{- define "ido.labels" -}}
app: ido
app.kubernetes.io/name: ido
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end }}

{{/*
셀렉터 레이블
*/}}
{{- define "ido.selectorLabels" -}}
app: ido
app.kubernetes.io/name: ido
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Phase 프리셋 처리 — --set phase=2a 시 featureFlags 자동 오버라이드
사용: {{ include "ido.phaseEnvVars" . }}
*/}}
{{- define "ido.phaseEnvVars" -}}
{{- $flags := .Values.featureFlags -}}
{{- $phase := .Values.phase | default "1" -}}

{{- if eq $phase "2a" }}
- name: IDO_PROVISIONING_ENABLED
  value: "true"
- name: IDO_PROVISIONING_DRY_RUN
  value: "true"
- name: IDO_PROVISIONING_RELAY_ENABLED
  value: "false"
{{- else if eq $phase "2b" }}
- name: IDO_PROVISIONING_ENABLED
  value: "true"
- name: IDO_PROVISIONING_DRY_RUN
  value: "false"
- name: IDO_PROVISIONING_RELAY_ENABLED
  value: "true"
{{- else if eq $phase "3a" }}
- name: IDO_PROVISIONING_ENABLED
  value: "true"
- name: IDO_PROVISIONING_DRY_RUN
  value: "false"
- name: IDO_PROVISIONING_RELAY_ENABLED
  value: "true"
- name: IDO_GATEWAY_INBOUND_ENABLED
  value: "true"
{{- else if eq $phase "3b" }}
- name: IDO_PROVISIONING_ENABLED
  value: "true"
- name: IDO_PROVISIONING_DRY_RUN
  value: "false"
- name: IDO_PROVISIONING_RELAY_ENABLED
  value: "true"
- name: IDO_GATEWAY_INBOUND_ENABLED
  value: "true"
- name: IDO_GATEWAY_OUTBOUND_ENABLED
  value: "true"
{{- else if eq $phase "4" }}
- name: IDO_PROVISIONING_ENABLED
  value: "true"
- name: IDO_PROVISIONING_DRY_RUN
  value: "false"
- name: IDO_PROVISIONING_RELAY_ENABLED
  value: "true"
- name: IDO_GATEWAY_INBOUND_ENABLED
  value: "true"
- name: IDO_GATEWAY_OUTBOUND_ENABLED
  value: "true"
- name: IDO_HMAC_SIG_REQUIRED
  value: "true"
{{- end }}
{{- end }}

{{/*
ImagePullSecrets 처리
*/}}
{{- define "ido.imagePullSecrets" -}}
{{- if .Values.imagePullSecrets }}
imagePullSecrets:
{{- range .Values.imagePullSecrets }}
  - name: {{ . }}
{{- end }}
{{- end }}
{{- end }}
