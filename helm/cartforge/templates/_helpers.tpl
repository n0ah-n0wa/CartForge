{{/*
Expand the name of the chart.
*/}}
{{- define "cartforge.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "cartforge.fullname" -}}
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

{{- define "cartforge.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "cartforge.commonLabels" -}}
helm.sh/chart: {{ include "cartforge.chart" . }}
app.kubernetes.io/name: {{ include "cartforge.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: cartforge
{{- end }}

{{- define "cartforge.labels" -}}
{{ include "cartforge.commonLabels" . }}
app.kubernetes.io/component: api
{{- end }}

{{- define "cartforge.selectorLabels" -}}
app.kubernetes.io/name: {{ include "cartforge.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: api
{{- end }}

{{- define "cartforge.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "cartforge.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "cartforge.image" -}}
{{- $tag := required "image.tag is required (use an immutable git SHA for production; Chart.AppVersion is never used as a fallback)" .Values.image.tag }}
{{- if contains "SNAPSHOT" $tag }}
{{- fail (printf "image.tag must not contain SNAPSHOT (got %q)" $tag) }}
{{- end }}
{{- printf "%s:%s" .Values.image.repository $tag }}
{{- end }}

{{- define "cartforge.secretName" -}}
{{- if .Values.secrets.existingSecret }}
{{- .Values.secrets.existingSecret }}
{{- else if .Values.secrets.create }}
{{- printf "%s-secrets" (include "cartforge.fullname" .) }}
{{- else }}
{{- fail "secrets.existingSecret is required when secrets.create is false" }}
{{- end }}
{{- end }}

{{- define "cartforge.validateSecrets" -}}
{{- if and .Values.secrets.create (not .Values.secrets.existingSecret) }}
{{- if not .Values.secrets.postgresPassword }}
{{- fail "secrets.postgresPassword is required when secrets.create is true (use --set-string or a values-secrets.yaml file)" }}
{{- end }}
{{- if not .Values.secrets.jwtSecret }}
{{- fail "secrets.jwtSecret is required when secrets.create is true (use --set-string or a values-secrets.yaml file)" }}
{{- end }}
{{- end }}
{{- if and .Values.secrets.create .Values.secrets.jwtSecret }}
{{- if lt (len .Values.secrets.jwtSecret) 32 }}
{{- fail (printf "secrets.jwtSecret must be at least 32 characters (got %d)" (len .Values.secrets.jwtSecret)) }}
{{- end }}
{{- end }}
{{- if and .Values.demoInfrastructure.redis.enabled (not .Values.redis.url) (not .Values.secrets.redisPassword) }}
{{- fail "demo Redis requires secrets.redisPassword (for REDIS_URL) or an explicit redis.url that includes AUTH credentials" }}
{{- end }}
{{- if eq .Values.config.springProfilesActive "prod" }}
{{- if not .Values.networkPolicy.enabled }}
{{- fail "networkPolicy.enabled must be true when config.springProfilesActive is prod (Prometheus and the API Service must not be cluster-wide reachable)" }}
{{- end }}
{{- if not .Values.ingress.tls }}
{{- fail "ingress.tls must be configured when config.springProfilesActive is prod (TLS terminates Bearer tokens in transit)" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "cartforge.postgresName" -}}
{{- printf "%s-postgres" (include "cartforge.fullname" .) }}
{{- end }}

{{- define "cartforge.redisName" -}}
{{- printf "%s-redis" (include "cartforge.fullname" .) }}
{{- end }}

{{- define "cartforge.databaseHost" -}}
{{- if .Values.demoInfrastructure.postgres.enabled }}
{{- include "cartforge.postgresName" . }}
{{- else }}
{{- required "database.host is required when demo PostgreSQL is disabled" .Values.database.host }}
{{- end }}
{{- end }}

{{- define "cartforge.databaseUrl" -}}
{{- if .Values.database.url }}
{{- .Values.database.url }}
{{- else }}
{{- printf "jdbc:postgresql://%s:%v/%s" (include "cartforge.databaseHost" .) .Values.database.port .Values.database.name }}
{{- end }}
{{- end }}

{{- define "cartforge.redisHost" -}}
{{- if .Values.demoInfrastructure.redis.enabled }}
{{- include "cartforge.redisName" . }}
{{- else }}
{{- required "redis.host is required when demo Redis is disabled" .Values.redis.host }}
{{- end }}
{{- end }}

{{- define "cartforge.redisUrl" -}}
{{- if .Values.redis.url }}
{{- .Values.redis.url }}
{{- else if and .Values.demoInfrastructure.redis.enabled .Values.secrets.redisPassword }}
{{- printf "redis://:%s@%s:%v" (.Values.secrets.redisPassword | urlquery) (include "cartforge.redisHost" .) .Values.redis.port }}
{{- else }}
{{- printf "redis://%s:%v" (include "cartforge.redisHost" .) .Values.redis.port }}
{{- end }}
{{- end }}
