$ErrorActionPreference = 'Continue'

$ScriptsRoot = $PSScriptRoot
$PowerShellExe = $null
try {
    $PowerShellExe = (Get-Process -Id $PID).Path
} catch {
    $PowerShellExe = $null
}
if ([string]::IsNullOrWhiteSpace($PowerShellExe)) {
    $PowerShellExe = 'powershell'
}

$FakeRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-desktop-harness-tests-' + [guid]::NewGuid().ToString('N'))
$Failures = 0

function Write-Pass {
    param([Parameter(Mandatory = $true)][string]$Name)
    Write-Host "[desktop-harness-test][PASS] $Name"
}

function Write-Fail {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:Failures++
    Write-Host "[desktop-harness-test][FAIL] $Name :: $Message"
}

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Condition,
        [string]$Message = 'assertion failed'
    )
    if ($Condition) { Write-Pass $Name } else { Write-Fail $Name $Message }
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )
    Assert-True $Name ($Text.Contains($Needle)) "expected output to contain '$Needle'; output=$Text"
}

function Assert-NotContains {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )
    Assert-True $Name (-not $Text.Contains($Needle)) "expected output not to contain '$Needle'; output=$Text"
}

function Set-TestFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [AllowEmptyString()][string]$Value
    )
    $parent = Split-Path -Parent $Path
    if ($parent) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
}

function Invoke-Harness {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [switch]$DeepScan
    )
    $scriptPath = Join-Path $script:ScriptsRoot 'desktop_safe_patch_harness.ps1'
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $scriptPath, '-Root', $Root, '-NoWrite')
    if ($DeepScan) {
        $arguments += '-DeepScan'
    }
    $lines = & $script:PowerShellExe @arguments 2>&1 |
        ForEach-Object { $_.ToString() }
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = ($lines -join "`n")
    }
}

try {
    New-Item -ItemType Directory -Force -Path (Join-Path $FakeRoot 'main\java') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $FakeRoot 'main\resources') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $FakeRoot 'app\src\main\java_clean') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $FakeRoot 'app\src\main\resources') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $FakeRoot '__patch_drop__') | Out-Null

    Set-TestFile (Join-Path $FakeRoot 'settings.gradle') "rootProject.name = 'fake-awx'`n"
    Set-TestFile (Join-Path $FakeRoot 'gradlew.bat') "@echo off`n"
    Set-TestFile (Join-Path $FakeRoot 'build.gradle.kts') @'
plugins { java }

sourceSets {
    main {
        java.srcDirs("main/java")
        resources.srcDirs("main/resources")
    }
}

dependencies {
    implementation("dev.langchain4j:langchain4j:1.0.1")
}
'@
    Set-TestFile (Join-Path $FakeRoot 'app\build.gradle.kts') @'
plugins { java }

sourceSets {
    val main by getting {
        java.setSrcDirs(listOf("src/main/java_clean"))
    }
}
'@

    $result = Invoke-Harness -Root $FakeRoot

    Assert-True 'desktop harness exits zero for fake non-git root' ($result.ExitCode -eq 0) "expected zero exit; output=$($result.Output)"
    Assert-Contains 'desktop harness reports unavailable git metadata' $result.Output '- gitMetadataAvailable: False'
    Assert-Contains 'desktop harness records git evidence_needed' $result.Output 'git metadata unavailable at root'
    Assert-NotContains 'desktop harness suppresses git usage banner' $result.Output 'These are common Git commands'
    Assert-NotContains 'desktop harness suppresses raw git usage line' $result.Output 'usage: git'
    Assert-True 'desktop harness accepts app val-main java_clean static declaration' `
        (-not $result.Output.Contains('[WARN] wrong-sourceset app/build.gradle.kts')) `
        'valid app val-main java_clean declaration was rejected'

    Set-TestFile (Join-Path $FakeRoot 'app\build.gradle.kts') @'
plugins { java }

// sourceSets { val main by getting {
//     java.setSrcDirs(listOf("src/main/java_clean"))
// } }
'@

    $appCommentOnlyResult = Invoke-Harness -Root $FakeRoot

    Assert-True 'desktop harness blocks comment-only app java_clean declaration' `
        ($appCommentOnlyResult.Output.Contains('[BLOCK] wrong-sourceset app/build.gradle.kts') -and `
            -not $appCommentOnlyResult.Output.Contains('[WARN] wrong-sourceset app/build.gradle.kts')) `
        'comment-only app java_clean declaration did not produce the ownership BLOCK'

    Set-TestFile (Join-Path $FakeRoot 'app\build.gradle.kts') @'
plugins { java }

sourceSets {
    val test by getting {
        java.setSrcDirs(listOf("src/main/java_clean"))
    }
}
'@

    $appTestOnlyResult = Invoke-Harness -Root $FakeRoot

    Assert-True 'desktop harness blocks test-only app java_clean declaration' `
        ($appTestOnlyResult.Output.Contains('[BLOCK] wrong-sourceset app/build.gradle.kts') -and `
            -not $appTestOnlyResult.Output.Contains('[WARN] wrong-sourceset app/build.gradle.kts')) `
        'app test sourceSet declaration did not produce the ownership BLOCK'

    Set-TestFile (Join-Path $FakeRoot 'app\build.gradle.kts') @'
plugins { java }

val quoteForJson = '"'
val title = "it's harmless"

sourceSets {
    val main by getting {
        java.setSrcDirs(listOf("src/main/java_clean"))
    }
}
val ordinaryCharacter = 'x'
'@

    Set-TestFile (Join-Path $FakeRoot 'build.gradle.kts') @'
plugins { java }

// sourceSets { main {
//     java.srcDirs("main/java")
//     resources.srcDirs("main/resources")
// } }

dependencies {
    implementation("dev.langchain4j:langchain4j:1.0.1")
}
'@

    $commentOnlySourceSetResult = Invoke-Harness -Root $FakeRoot

    Assert-True 'desktop harness rejects comment-only root Java sourceSet proof' `
        ($commentOnlySourceSetResult.Output.Contains('[BLOCK] wrong-sourceset main/java')) `
        'comment-only Java declaration suppressed the wrong-sourceset BLOCK'
    Assert-True 'desktop harness rejects comment-only root resources sourceSet proof' `
        ($commentOnlySourceSetResult.Output.Contains('[BLOCK] wrong-sourceset main/resources')) `
        'comment-only resources declaration suppressed the wrong-sourceset BLOCK'
    Assert-True 'desktop harness accepts app sourceSet after Kotlin quote character literal' `
        (-not $commentOnlySourceSetResult.Output.Contains('[WARN] wrong-sourceset app/build.gradle.kts')) `
        'Kotlin quote character literal caused the valid app main sourceSet declaration to be rejected'

    Set-TestFile (Join-Path $FakeRoot 'build.gradle.kts') @'
plugins { java }

/* outer documentation
   /* nested documentation */
   sourceSets { main {
       java.srcDirs("main/java")
       resources.srcDirs("main/resources")
   } }
*/

dependencies {
    implementation("dev.langchain4j:langchain4j:1.0.1")
}
'@

    $nestedCommentSourceSetResult = Invoke-Harness -Root $FakeRoot

    Assert-True 'desktop harness rejects nested-comment root Java sourceSet proof' `
        ($nestedCommentSourceSetResult.Output.Contains('[BLOCK] wrong-sourceset main/java')) `
        'nested-comment Java declaration suppressed the wrong-sourceset BLOCK'
    Assert-True 'desktop harness rejects nested-comment root resources sourceSet proof' `
        ($nestedCommentSourceSetResult.Output.Contains('[BLOCK] wrong-sourceset main/resources')) `
        'nested-comment resources declaration suppressed the wrong-sourceset BLOCK'

    Set-TestFile (Join-Path $FakeRoot 'build.gradle.kts') @'
plugins { java }

sourceSets {
    main {
        java.srcDirs(__AWX_MAIN_JAVA__)
        resources.srcDirs(__AWX_MAIN_RESOURCES__)
    }
}

dependencies {
    implementation("dev.langchain4j:langchain4j:1.0.1")
}
'@

    $placeholderIdentifierResult = Invoke-Harness -Root $FakeRoot

    Assert-True 'desktop harness rejects internal-marker Java identifier' `
        ($placeholderIdentifierResult.Output.Contains('[BLOCK] wrong-sourceset main/java')) `
        'raw Java placeholder identifier was accepted as a path literal'
    Assert-True 'desktop harness rejects internal-marker resources identifier' `
        ($placeholderIdentifierResult.Output.Contains('[BLOCK] wrong-sourceset main/resources')) `
        'raw resources placeholder identifier was accepted as a path literal'

    Set-TestFile (Join-Path $FakeRoot 'build.gradle.kts') @'
plugins { java }

sourceSets {
    main {
        fakeJava.srcDirs("main/java")
        fakeResources.srcDirs("main/resources")
        java.srcDirs("main/resources")
        resources.srcDirs("main/java")
    }
}

dependencies {
    implementation("dev.langchain4j:langchain4j:1.0.1")
}
'@

    $wrongReceiverResult = Invoke-Harness -Root $FakeRoot

    Assert-True 'desktop harness rejects wrong-receiver root Java proof' `
        ($wrongReceiverResult.Output.Contains('[BLOCK] wrong-sourceset main/java')) `
        'wrong receiver or swapped kind certified root Java ownership'
    Assert-True 'desktop harness rejects wrong-receiver root resources proof' `
        ($wrongReceiverResult.Output.Contains('[BLOCK] wrong-sourceset main/resources')) `
        'wrong receiver or swapped kind certified root resources ownership'

    Set-TestFile (Join-Path $FakeRoot 'build.gradle.kts') @'
plugins { java }

sourceSets {
    main {
        decoy {
            java { srcDirs("main/java") }
            resources { srcDirs("main/resources") }
        }
    }
}

dependencies {
    implementation("dev.langchain4j:langchain4j:1.0.1")
}
'@

    $nestedReceiverResult = Invoke-Harness -Root $FakeRoot

    Assert-True 'desktop harness rejects nested-decoy root Java proof' `
        ($nestedReceiverResult.Output.Contains('[BLOCK] wrong-sourceset main/java')) `
        'non-direct Java block certified root Java ownership'
    Assert-True 'desktop harness rejects nested-decoy root resources proof' `
        ($nestedReceiverResult.Output.Contains('[BLOCK] wrong-sourceset main/resources')) `
        'non-direct resources block certified root resources ownership'

    Set-TestFile (Join-Path $FakeRoot 'build.gradle.kts') @'
plugins { java }

sourceSets {
    main {
        java {
            fake.srcDirs("main/java")
        }
        resources {
            fake.srcDirs("main/resources")
        }
    }
}

dependencies {
    implementation("dev.langchain4j:langchain4j:1.0.1")
}
'@

    $nestedWrongReceiverResult = Invoke-Harness -Root $FakeRoot

    Assert-True 'desktop harness rejects nested wrong-receiver Java proof' `
        ($nestedWrongReceiverResult.Output.Contains('[BLOCK] wrong-sourceset main/java')) `
        'qualified fake call inside Java block certified ownership'
    Assert-True 'desktop harness rejects nested wrong-receiver resources proof' `
        ($nestedWrongReceiverResult.Output.Contains('[BLOCK] wrong-sourceset main/resources')) `
        'qualified fake call inside resources block certified ownership'

    Set-TestFile (Join-Path $FakeRoot 'build.gradle.kts') @'
plugins { java }

sourceSets {
    main {
        val java = object { fun srcDirs(path: String) {} }
        val resources = object { fun srcDirs(path: String) {} }
        java.srcDirs("main/java")
        resources.srcDirs("main/resources")
    }
}

dependencies {
    implementation("dev.langchain4j:langchain4j:1.0.1")
}
'@

    $semanticShadowResult = Invoke-Harness -Root $FakeRoot
    $runtimeSourceSetEvidenceNeeded = 'Gradle runtime source-set ownership was not observed; verify with .\gradlew.bat sourceSets --console=plain and the focused test command before applying a source change.'

    Assert-True 'desktop harness labels sourceSet evidence as static structural' `
        ($semanticShadowResult.Output.Contains('- sourceSetEvidenceKind: static-structural')) `
        'lexical sourceSet result lacked a static-only evidence label'
    Assert-True 'desktop harness reports runtime sourceSet resolution not observed' `
        ($semanticShadowResult.Output.Contains('- runtimeSourceSetResolution: not_observed')) `
        'lexical sourceSet result was not bounded from runtime resolution'
    Assert-True 'desktop harness requires Gradle sourceSet verification' `
        ($semanticShadowResult.Output.Contains($runtimeSourceSetEvidenceNeeded)) `
        'semantic sourceSet ownership verification was not requested'
    Assert-True 'desktop harness emits no runtime ownership success claim' `
        (-not $semanticShadowResult.Output.Contains('runtimeSourceSetResolution: verified') -and `
         -not $semanticShadowResult.Output.Contains('runtime ownership proven')) `
        'static match was promoted to a runtime ownership success claim'

    Set-TestFile (Join-Path $FakeRoot 'build.gradle.kts') @'
plugins { java }

sourceSets {
    main {
        java {
            srcDirs("main/java")
        }
        resources {
            srcDirs("main/resources")
        }
    }
}

dependencies {
    implementation("dev.langchain4j:langchain4j:1.0.1")
}
'@

    $nestedMainResult = Invoke-Harness -Root $FakeRoot

    Assert-True 'desktop harness accepts nested Java main sourceSet proof' `
        (-not $nestedMainResult.Output.Contains('[BLOCK] wrong-sourceset main/java')) `
        'valid nested Java main block was rejected'
    Assert-True 'desktop harness accepts nested resources main sourceSet proof' `
        (-not $nestedMainResult.Output.Contains('[BLOCK] wrong-sourceset main/resources')) `
        'valid nested resources main block was rejected'

    Set-TestFile (Join-Path $FakeRoot 'build.gradle.kts') @'
plugins { java }

val documentedExample = """
sourceSets { main {
    java.srcDirs("main/java")
    resources.srcDirs("main/resources")
} }
"""

dependencies {
    implementation("dev.langchain4j:langchain4j:1.0.1")
}
'@

    $stringOnlySourceSetResult = Invoke-Harness -Root $FakeRoot

    Assert-True 'desktop harness rejects string-only root Java sourceSet proof' `
        ($stringOnlySourceSetResult.Output.Contains('[BLOCK] wrong-sourceset main/java')) `
        'string-only Java declaration suppressed the wrong-sourceset BLOCK'
    Assert-True 'desktop harness rejects string-only root resources sourceSet proof' `
        ($stringOnlySourceSetResult.Output.Contains('[BLOCK] wrong-sourceset main/resources')) `
        'string-only resources declaration suppressed the wrong-sourceset BLOCK'

    Set-TestFile (Join-Path $FakeRoot 'build.gradle.kts') @'
plugins { java }

sourceSets {
    test {
        java.srcDirs("main/java")
        resources.srcDirs("main/resources")
    }
}

dependencies {
    implementation("dev.langchain4j:langchain4j:1.0.1")
}
'@

    $testOnlySourceSetResult = Invoke-Harness -Root $FakeRoot

    Assert-True 'desktop harness rejects test-only root Java sourceSet proof' `
        ($testOnlySourceSetResult.Output.Contains('[BLOCK] wrong-sourceset main/java')) `
        'test sourceSet Java declaration suppressed the main wrong-sourceset BLOCK'
    Assert-True 'desktop harness rejects test-only root resources sourceSet proof' `
        ($testOnlySourceSetResult.Output.Contains('[BLOCK] wrong-sourceset main/resources')) `
        'test sourceSet resources declaration suppressed the main wrong-sourceset BLOCK'

    Set-TestFile (Join-Path $FakeRoot 'build.gradle.kts') @'
plugins { java }

val langchain4jVersion = "1.0.1"

sourceSets {
    main {
        java.srcDirs(listOf("main/java"))
        resources.srcDirs(listOf("main/resources"))
    }
}

dependencies {
    implementation("dev.langchain4j:langchain4j:$langchain4jVersion")
    implementation("dev.langchain4j:langchain4j-open-ai:${langchain4jVersion}")
}
'@

    $variableVersionResult = Invoke-Harness -Root $FakeRoot

    Assert-True 'desktop harness exits zero for langchain variable version fixture' ($variableVersionResult.ExitCode -eq 0) "expected zero exit; output=$($variableVersionResult.Output)"
    Assert-Contains 'desktop harness resolves langchain variable versions' $variableVersionResult.Output '- version 1.0.1: 2'
    Assert-NotContains 'desktop harness does not block resolved langchain variable version' $variableVersionResult.Output '[BLOCK] langchain4j-version-purity'
    Assert-True 'desktop harness accepts listOf root Java main sourceSet proof' `
        (-not $variableVersionResult.Output.Contains('[BLOCK] wrong-sourceset main/java')) `
        'valid listOf Java main declaration was rejected'
    Assert-True 'desktop harness accepts listOf root resources main sourceSet proof' `
        (-not $variableVersionResult.Output.Contains('[BLOCK] wrong-sourceset main/resources')) `
        'valid listOf resources main declaration was rejected'

    Set-TestFile (Join-Path $FakeRoot 'main\java\com\example\HeaderExamples.java') @'
package com.example;

final class HeaderExamples {
    /**
     * Authorization: Bearer <token>
     * Cookie: aw-admin-token=<token>
     */
    void loop(jakarta.servlet.http.Cookie[] cookies) {
        for (jakarta.servlet.http.Cookie cookie : cookies) {
            cookie.getName();
        }
    }
}
'@

    $headerResult = Invoke-Harness -Root $FakeRoot -DeepScan

    Assert-True 'desktop harness exits zero for generic header examples' ($headerResult.ExitCode -eq 0) "expected zero exit; output=$($headerResult.Output)"
    Assert-Contains 'desktop harness ignores generic header examples as high-confidence secrets' $headerResult.Output '- secretPatternHits: 0'
    Assert-NotContains 'desktop harness generic header examples do not block' $headerResult.Output '[BLOCK] secret-leak-risk'

    Set-TestFile (Join-Path $FakeRoot 'main\java\com\example\GuardedCancellationExample.java') @'
package com.example;

final class GuardedCancellationExample {
    /**
     * Documents why raw Future.cancel(true) is unsafe.
     */
    void guarded(java.util.concurrent.ExecutorService delegate,
            java.util.Collection<? extends java.util.concurrent.Callable<Boolean>> tasks,
            long timeout,
            java.util.concurrent.TimeUnit unit) throws Exception {
        new ai.abandonware.nova.boot.exec.CancelShieldExecutorService(delegate, "fixture")
                .invokeAll(tasks, timeout, unit);
    }
}
'@
    Set-TestFile (Join-Path $FakeRoot 'main\java\com\example\RawCancelExample.java') @'
package com.example;

final class RawCancelExample {
    void risky(java.util.concurrent.Future<?> future) {
        future.cancel(true);
    }
}
'@

    $cancelResult = Invoke-Harness -Root $FakeRoot -DeepScan

    Assert-True 'desktop harness exits zero for cancellation fixtures' ($cancelResult.ExitCode -eq 0) "expected zero exit; output=$($cancelResult.Output)"
    Assert-Contains 'desktop harness separates untriaged cancellation risks' $cancelResult.Output '- untriaged cancellation risk count: 1'
    Assert-Contains 'desktop harness reports raw cancel true path' $cancelResult.Output 'main/java/com/example/RawCancelExample.java: 1'
    Assert-Contains 'desktop harness warns on untriaged cancellation risk' $cancelResult.Output '[WARN] cancellation-toxicity-risk'

    $fakeKey = 'sk-' + ('A' * 24)
    Set-TestFile (Join-Path $FakeRoot 'main\java\com\example\FakeSecretExample.java') "package com.example; final class FakeSecretExample { String key = `"$fakeKey`"; }`n"

    $secretResult = Invoke-Harness -Root $FakeRoot -DeepScan

    Assert-True 'desktop harness exits zero with fake secret fixture' ($secretResult.ExitCode -eq 0) "expected zero exit; output=$($secretResult.Output)"
    Assert-Contains 'desktop harness still counts high-confidence key patterns' $secretResult.Output '- secretPatternHits: 1'
    Assert-Contains 'desktop harness still blocks high-confidence key patterns' $secretResult.Output '[BLOCK] secret-leak-risk'

    Set-TestFile (Join-Path $FakeRoot 'main\java\com\example\SupabaseSecretExample.java') "package com.example; final class SupabaseSecretExample { String key = `"sb_secret_$('A' * 24)`"; }`n"

    $supabaseSecretResult = Invoke-Harness -Root $FakeRoot -DeepScan

    Assert-True 'desktop harness exits zero with supabase secret fixture' ($supabaseSecretResult.ExitCode -eq 0) "expected zero exit; output=$($supabaseSecretResult.Output)"
    Assert-Contains 'desktop harness counts supabase sb key prefixes' $supabaseSecretResult.Output '- secretPatternHits: 2'
    Assert-Contains 'desktop harness blocks supabase sb key prefixes' $supabaseSecretResult.Output '[BLOCK] secret-leak-risk'

    Set-TestFile (Join-Path $FakeRoot 'main\java\com\example\SupabaseAccessTokenExample.java') "package com.example; final class SupabaseAccessTokenExample { String key = `"sbp_$('B' * 24)`"; }`n"

    $supabasePatResult = Invoke-Harness -Root $FakeRoot -DeepScan

    Assert-True 'desktop harness exits zero with supabase access token fixture' ($supabasePatResult.ExitCode -eq 0) "expected zero exit; output=$($supabasePatResult.Output)"
    Assert-Contains 'desktop harness counts supabase access token prefix' $supabasePatResult.Output '- secretPatternHits: 3'
    Assert-Contains 'desktop harness blocks supabase access token prefix' $supabasePatResult.Output '[BLOCK] secret-leak-risk'
} catch {
    Write-Fail 'unexpected exception' $_.Exception.Message
} finally {
    if (Test-Path $FakeRoot) {
        Remove-Item -LiteralPath $FakeRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if ($Failures -gt 0) {
    Write-Host "[desktop-harness-test][SUMMARY] failed=$Failures"
    exit 1
}

Write-Host '[desktop-harness-test][SUMMARY] failed=0'
exit 0
