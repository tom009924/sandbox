#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";

// Target dependencies to evaluate OpenSSF Scorecard health
const DEPENDENCIES = [
  { name: "Spring Boot", repo: "spring-projects/spring-boot", category: "Framework" },
  { name: "Spring Cloud GCP", repo: "GoogleCloudPlatform/spring-cloud-gcp", category: "Cloud Integration" },
  { name: "Google Cloud Java SDK (Spanner & Pub/Sub)", repo: "googleapis/google-cloud-java", category: "Cloud Database & Messaging" },
  { name: "Flyway", repo: "flyway/flyway", category: "Database Migration" },
  { name: "Jackson Databind", repo: "FasterXML/jackson-databind", category: "JSON Serialization" },
  { name: "JJWT", repo: "jwtk/jjwt", category: "Security / JWT" },
  { name: "Bouncy Castle", repo: "bcgit/bc-java", category: "Cryptography" },
  { name: "WireMock", repo: "wiremock/wiremock", category: "Testing" },
  { name: "ArchUnit", repo: "TNG/ArchUnit", category: "Architecture Testing" },
  { name: "REST Assured", repo: "rest-assured/rest-assured", category: "API Testing" },
  { name: "Swagger Parser", repo: "swagger-api/swagger-parser", category: "OpenAPI Specification" },
  { name: "Detekt", repo: "detekt/detekt", category: "Static Analysis" }
];

// Display labels and category mapping for OpenSSF Scorecard checks
const CHECK_METADATA = {
  "Maintained": { label: "Maintenance Activity (Commit/Issue)", category: "Sustainability" },
  "Code-Review": { label: "Code Review Enforced", category: "Sustainability" },
  "Dangerous-Workflow": { label: "Workflow Security (No Injection)", category: "Supply Chain" },
  "Token-Permissions": { label: "Least-Privilege CI Tokens", category: "Supply Chain" },
  "Branch-Protection": { label: "Branch Protection Rules", category: "Supply Chain" },
  "Pinned-Dependencies": { label: "Pinned Dependencies (Hash)", category: "Supply Chain" },
  "Binary-Artifacts": { label: "No Binary Artifacts in Source", category: "Supply Chain" },
  "Security-Policy": { label: "Security Policy (SECURITY.md)", category: "Governance" },
  "License": { label: "License Declared", category: "Governance" },
  "Signed-Releases": { label: "Cryptographically Signed Releases", category: "Governance" },
  "CII-Best-Practices": { label: "OpenSSF Best Practices Badge", category: "Governance" },
  "SAST": { label: "Static Analysis (SAST/CodeQL)", category: "Code Quality" },
  "Fuzzing": { label: "Continuous Fuzzing", category: "Code Quality" },
  "Packaging": { label: "Packaging Workflow", category: "Supply Chain" },
  "Vulnerabilities": { label: "Unfixed Vulnerabilities", category: "Security" }
};

async function fetchScorecard(repo) {
  const url = `https://api.securityscorecards.dev/projects/github.com/${repo}`;
  try {
    const res = await fetch(url, { headers: { "User-Agent": "oss-health-checker" } });
    if (!res.ok) {
      return { score: null, checks: [], error: `HTTP ${res.status}` };
    }
    const data = await res.json();
    const rawChecks = data.checks || [];

    const checks = rawChecks.map((c) => ({
      name: c.name,
      label: CHECK_METADATA[c.name]?.label || c.name,
      category: CHECK_METADATA[c.name]?.category || "Other",
      score: c.score,
      reason: c.reason || "No detailed reason provided",
      details: c.details || []
    }));

    return {
      score: data.score !== undefined ? data.score.toFixed(1) : "N/A",
      date: data.date || new Date().toISOString().split("T")[0],
      commit: data.repo?.commit || "N/A",
      checks,
      error: null
    };
  } catch (err) {
    return { score: null, checks: [], error: err.message };
  }
}

function getCheckScore(checks, name) {
  const c = checks.find((item) => item.name === name);
  return c ? c.score : -1;
}

function getCheckReason(checks, name) {
  const c = checks.find((item) => item.name === name);
  return c ? c.reason : "N/A";
}

function formatScore(val) {
  if (val === null || val === undefined || val === -1 || val === "N/A") return "⚪ N/A";
  const num = typeof val === "number" ? val : parseFloat(val);
  if (isNaN(num)) return "⚪ N/A";
  if (num >= 7.0) return `🟢 ${num.toFixed(1)}`;
  if (num >= 4.0) return `🟡 ${num.toFixed(1)}`;
  return `🔴 ${num.toFixed(1)}`;
}

function formatCheck(val) {
  if (val === null || val === undefined || val < 0) return "⚪ N/A";
  if (val >= 8) return `🟢 ${val}/10`;
  if (val >= 4) return `🟡 ${val}/10`;
  return `🔴 ${val}/10`;
}

function formatIconOnly(val) {
  if (val === null || val === undefined || val < 0) return "⚪";
  if (val >= 8) return "🟢";
  if (val >= 4) return "🟡";
  return "🔴";
}

async function main() {
  const args = process.argv.slice(2);
  const jsonOutIdx = args.indexOf("--json-output");
  const jsonPath = jsonOutIdx !== -1 && args[jsonOutIdx + 1]
    ? args[jsonOutIdx + 1]
    : "build/reports/oss-health-report.json";

  console.log("🔍 Evaluating Comprehensive OpenSSF Scorecard Health for OSS Dependencies...\n");
  const results = [];
  const riskHighlights = [];

  for (const dep of DEPENDENCIES) {
    process.stdout.write(`Fetching ${dep.name} (${dep.repo})... `);
    const data = await fetchScorecard(dep.repo);
    console.log(data.score !== null ? `Score: ${data.score}/10` : `(${data.error})`);

    const scoreNum = data.score !== "N/A" && data.score !== null ? parseFloat(data.score) : null;
    const maintainedScore = getCheckScore(data.checks, "Maintained");
    const dangerousWorkflowScore = getCheckScore(data.checks, "Dangerous-Workflow");
    const codeReviewScore = getCheckScore(data.checks, "Code-Review");

    // Identify risk factors
    if (scoreNum !== null && scoreNum < 4.0) {
      riskHighlights.push({
        name: dep.name,
        repo: dep.repo,
        type: "Low Overall Score",
        severity: "HIGH",
        detail: `Overall score is ${data.score}/10.`
      });
    }
    if (maintainedScore >= 0 && maintainedScore <= 2) {
      riskHighlights.push({
        name: dep.name,
        repo: dep.repo,
        type: "Low Maintenance / Abandonment Risk",
        severity: "MEDIUM",
        detail: getCheckReason(data.checks, "Maintained")
      });
    }
    if (dangerousWorkflowScore >= 0 && dangerousWorkflowScore <= 3) {
      riskHighlights.push({
        name: dep.name,
        repo: dep.repo,
        type: "Dangerous Workflow Pattern",
        severity: "HIGH",
        detail: getCheckReason(data.checks, "Dangerous-Workflow")
      });
    }

    results.push({
      ...dep,
      ...data,
      summaryChecks: {
        maintained: maintainedScore,
        codeReview: codeReviewScore,
        securityPolicy: getCheckScore(data.checks, "Security-Policy"),
        dangerousWorkflow: dangerousWorkflowScore,
        tokenPermissions: getCheckScore(data.checks, "Token-Permissions"),
        branchProtection: getCheckScore(data.checks, "Branch-Protection"),
        sast: getCheckScore(data.checks, "SAST")
      }
    });
  }

  // Print Summary Table in Console
  console.log("\n### 📊 OpenSSF Scorecard Overview");
  console.log("| Dependency | Repository | Category | Score (0-10) | Maintained | Code Review | CI Security | Sec Policy |");
  console.log("|---|---|---|---|---|---|---|---|");
  for (const r of results) {
    const ciSecurity = Math.min(
      r.summaryChecks.dangerousWorkflow >= 0 ? r.summaryChecks.dangerousWorkflow : 10,
      r.summaryChecks.tokenPermissions >= 0 ? r.summaryChecks.tokenPermissions : 10
    );
    console.log(
      `| ${r.name} | [${r.repo}](https://github.com/${r.repo}) | ${r.category} | ${formatScore(r.score)} | ${formatCheck(r.summaryChecks.maintained)} | ${formatCheck(r.summaryChecks.codeReview)} | ${formatCheck(ciSecurity)} | ${formatCheck(r.summaryChecks.securityPolicy)} |`
    );
  }

  // Print Risk Highlights
  if (riskHighlights.length > 0) {
    console.log("\n⚠️ Attention / Risk Highlights Detected in Upstream Dependencies:");
    for (const risk of riskHighlights) {
      console.log(`- [${risk.severity}] **${risk.name}** (${risk.type}): ${risk.detail}`);
    }
  } else {
    console.log("\n✅ No critical supply chain or maintenance risks detected across dependencies.");
  }

  // Write JSON report
  const reportDir = path.dirname(jsonPath);
  if (!fs.existsSync(reportDir)) {
    fs.mkdirSync(reportDir, { recursive: true });
  }
  const jsonReport = {
    generatedAt: new Date().toISOString(),
    evaluatedDependenciesCount: results.length,
    riskHighlights,
    results
  };
  fs.writeFileSync(jsonPath, JSON.stringify(jsonReport, null, 2), "utf-8");
  console.log(`\n💾 Detailed JSON report written to: ${jsonPath}`);

  // Generate Rich GitHub Step Summary
  const summaryFile = process.env.GITHUB_STEP_SUMMARY;
  if (summaryFile) {
    let md = `## 🛡️ Comprehensive OpenSSF OSS Health & Sustainability Report\n\n`;
    md += `Evaluated **${results.length} core dependencies** across maintenance sustainability, supply chain integrity, CI/CD security posture, and security governance.\n\n`;

    // Overview Table
    md += `### 📊 Health Overview\n\n`;
    md += `| Dependency | Category | Score (0-10) | Maintained | Code Review | CI Security | Sec Policy |\n`;
    md += `|:---|:---|:---:|:---:|:---:|:---:|:---:|\n`;
    for (const r of results) {
      const ciSecurity = Math.min(
        r.summaryChecks.dangerousWorkflow >= 0 ? r.summaryChecks.dangerousWorkflow : 10,
        r.summaryChecks.tokenPermissions >= 0 ? r.summaryChecks.tokenPermissions : 10
      );
      md += `| **[${r.name}](https://github.com/${r.repo})** | ${r.category} | ${formatScore(r.score)} | ${formatCheck(r.summaryChecks.maintained)} | ${formatCheck(r.summaryChecks.codeReview)} | ${formatCheck(ciSecurity)} | ${formatCheck(r.summaryChecks.securityPolicy)} |\n`;
    }
    md += `\n*🟢 High (>=7.0 / 8-10) | 🟡 Moderate (4.0-6.9 / 4-7) | 🔴 Low / Risk (<4.0) | ⚪ N/A*\n\n`;

    // Risks / Attention Section
    if (riskHighlights.length > 0) {
      md += `### ⚠️ Supply Chain & Sustainability Observations\n\n`;
      for (const risk of riskHighlights) {
        md += `- **[${risk.name}](https://github.com/${risk.repo})** (${risk.type}): ${risk.detail}\n`;
      }
      md += `\n`;
    }

    // Expandable Detailed Breakdown Per Dependency
    md += `### 🔍 Detailed Scorecard Breakdown per Dependency\n\n`;
    md += `Click on any dependency below to inspect detailed scores, rationale, and specific security checks:\n\n`;

    for (const r of results) {
      md += `<details>\n`;
      md += `<summary><strong>${formatIconOnly(r.score)} ${r.name}</strong> — Overall Score: <code>${r.score}/10</code> (Repository: <code>${r.repo}</code>)</summary>\n\n`;
      md += `| Category | Check | Score | Assessment Rationale |\n`;
      md += `|:---|:---|:---:|:---|\n`;

      if (r.checks && r.checks.length > 0) {
        for (const c of r.checks) {
          const scoreDisplay = c.score >= 0 ? `${formatIconOnly(c.score)} ${c.score}/10` : `⚪ N/A`;
          md += `| ${c.category} | **${c.label}** | ${scoreDisplay} | ${c.reason} |\n`;
        }
      } else {
        md += `| - | Evaluation Data | ⚪ N/A | ${r.error || "No check details available"} |\n`;
      }
      md += `\n</details>\n\n`;
    }

    fs.appendFileSync(summaryFile, md);
  }
}

main().catch((err) => {
  console.error("Failed to evaluate OSS health:", err);
  process.exit(1);
});
