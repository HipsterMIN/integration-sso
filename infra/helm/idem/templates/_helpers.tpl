{{/*
OnePass Helm Chart — 공통 헬퍼 함수
*/}}

{{/* Chart 이름 */}}
{{- define "onepass.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* 풀네임 */}}
{{- define "onepass.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/* Chart 레이블 */}}
{{- define "onepass.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* 공통 레이블 */}}
{{- define "onepass.labels" -}}
helm.sh/chart: {{ include "onepass.chart" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/part-of: onepass
environment: {{ .Values.global.env }}
{{- end }}

{{/* Selector 레이블 */}}
{{- define "onepass.selectorLabels" -}}
app.kubernetes.io/name: {{ include "onepass.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/* 이미지 레퍼런스 생성 */}}
{{- define "onepass.image" -}}
{{- $registry := .Values.global.imageRegistry -}}
{{- $repository := .repository -}}
{{- $tag := .tag | default "latest" -}}
{{- if $registry -}}
{{- printf "%s/%s:%s" $registry $repository $tag -}}
{{- else -}}
{{- printf "%s:%s" $repository $tag -}}
{{- end -}}
{{- end }}
