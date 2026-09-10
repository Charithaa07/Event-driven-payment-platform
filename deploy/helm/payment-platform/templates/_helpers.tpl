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
{{- if .Values.serviceAccount.create -}}
{{- default (include "payment-platform.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
