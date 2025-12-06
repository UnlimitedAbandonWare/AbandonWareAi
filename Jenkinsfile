pipeline {
  agent any
  stages {
    stage('build') {
      steps {
        sh './gradlew clean build -x test || true'
      }
    }
    stage('soak:regression') {
      steps {
        sh "curl -sS -H 'X-Admin-Token: ${PROBE_TOKEN}' 'http://localhost:8080/internal/soak/run?k=100&topic=all&plan=brave&modes=anger,extremeZ&budgetMs=9000'"
      }
    }
  }
  post {
    always {
      archiveArtifacts artifacts: 'build/**/*.json, **/soak/*.ndjson', fingerprint: true, onlyIfSuccessful: false
    }
  }
}
