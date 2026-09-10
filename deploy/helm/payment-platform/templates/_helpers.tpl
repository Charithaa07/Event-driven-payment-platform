{{- define "payment-platform.name" -}}
payment-platform
{{- end -}}

{{- define "payment-platform.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name (include "payment-platform.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "payment-platform.serviceName" -}}
{{- printf "%s-%s" (include "payment-platform.fullname" .root) .name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "payment-platform.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ include "payment-platform.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part-of: event-driven-payment-platform
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "payment-platform.serviceAccountName" -}}
{{- $root := .root -}}
{{- $name := .name -}}
{{- if $root.Values.serviceAccount.create -}}
  {{- if $root.Values.serviceAccount.perService -}}
    {{- include "payment-platform.serviceName" (dict "root" $root "name" $name) -}}
  {{- else -}}
    {{- default (include "payment-platform.fullname" $root) $root.Values.serviceAccount.name -}}
  {{- end -}}
{{- else -}}
  {{- default "default" $root.Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
