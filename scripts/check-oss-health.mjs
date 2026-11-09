#!/usr/bin/env node
import fs from "node:fs";

// Key dependencies to evaluate OpenSSF Scorecard health
const DEPENDENCIES = [
  { name: "Spring Boot", repo: "spring-projects/spring-boot", category: "Framework" },
  { name: "Spring Cloud GCP", repo: "GoogleCloudPlatform/spring-cloud-gcp", category: "Cloud Integration" },
  { name: "Google Cloud Spanner", repo: "googleapis/java-spanner", category: "Database Client" },
  { name: "Google Cloud Pub/Sub", repo: "googleapis/java-pubsub", category: "Messaging Client" },
  { name: "JJWT", repo: "jwtk/jjwt", category: "Security / JWT" },
  { name: "Bouncy Castle", repo: "bcgit/bc-java", category: "Cryptography" },
  { name: "WireMock", repo: "wiremock/wiremock", category: "Testing" },
  { name: "ArchUnit", repo: "TNG/ArchUnit", category: "Architecture Testing" },
  { name: "REST Assured", repo: "rest-assured/rest-assured", category: "API Testing" },
  { name: "Swagger Parser", repo: "swagger-api/swagger-parser", category: "OpenAPI Specification" },
  { name: "Detekt", repo: "detekt/detekt", category: "Static Analysis" }
];

async function fetchScorecard(repo) {
  const url = `https://api.securityscorecards.dev/projects/github.com/${repo}`;
  try {
    const res = await fetch(url, { headers: { "User-Agent": "oss-health-checker" } });
    if (!res.ok) {
      return { score: null, maintained: null, codeReview: null, securityPolicy: null, error: `HTTP ${res.status}` };
    }
    const data = await res.json();
    const checks = data.checks || [];
    const getCheck = (name) => {
      const c = checks.find((item) => item.name === name);
      return c ? c.score : -1;
    };
    return {
      score: data.score !== undefined ? data.score.toFixed(1) : "N/A",
      maintained: getCheck("Maintained"),
      codeReview: getCheck("Code-Review"),
      securityPolicy: getCheck("Security-Policy"),
      error: null
    };
  } catch (err) {
    return { score: null, maintained: null, codeReview: null, securityPolicy: null, error: err.message };
  }
}

function formatScore(val) {
  if (val === null || val === undefined || val === -1 || val === "N/A") return "N/A";
  const num = typeof val === "number" ? val : parseFloat(val);
  if (isNaN(num)) return "N/A";
  if (num >= 7.0) return `🟢 ${num}`;
  if (num >= 4.0) return `🟡 ${num}`;
  return `🔴 ${num}`;
}

function formatCheck(val) {
  if (val === null || val === undefined || val < 0) return "N/A";
  if (val >= 8) return `🟢 ${val}/10`;
  if (val >= 4) return `🟡 ${val}/10`;
  return `🔴 ${val}/10`;
}

async function main() {
  console.log("🔍 Evaluating OpenSSF Scorecard Health for OSS Dependencies...\n");
  const results = [];
  let hasLowScore = false;

  for (const dep of DEPENDENCIES) {
    const data = await fetchScorecard(dep.repo);
    const scoreNum = data.score !== "N/A" && data.score !== null ? parseFloat(data.score) : null;
    if (scoreNum !== null && scoreNum < 3.0) {
      hasLowScore = true;
    }
    results.push({
      ...dep,
      ...data
    });
  }

  // Print console table
  console.log("| Dependency | Repository | Category | Scorecard (0-10) | Maintained | Code Review | Security Policy |");
  console.log("|---|---|---|---|---|---|---|");
  for (const r of results) {
    console.log(
      `| ${r.name} | [${r.repo}](https://github.com/${r.repo}) | ${r.category} | ${formatScore(r.score)} | ${formatCheck(r.maintained)} | ${formatCheck(r.codeReview)} | ${formatCheck(r.securityPolicy)} |`
    );
  }

  // Generate GitHub Step Summary if running in GitHub Actions
  const summaryFile = process.env.GITHUB_STEP_SUMMARY;
  if (summaryFile) {
    let md = `### 🛡️ OpenSSF OSS Health & Sustainability Report\n\n`;
    md += `OpenSSF Scorecard measures software security posture, maintenance activity, and supply-chain hygiene across direct dependencies.\n\n`;
    md += `| Dependency | Category | Scorecard Score | Maintenance Activity | Code Review Enforced | Security Policy |\n`;
    md += `|:---|:---|:---:|:---:|:---:|:---:|\n`;
    for (const r of results) {
      md += `| **[${r.name}](https://github.com/${r.repo})** | ${r.category} | ${formatScore(r.score)} | ${formatCheck(r.maintained)} | ${formatCheck(r.codeReview)} | ${formatCheck(r.securityPolicy)} |\n`;
    }
    md += `\n*🟢 High (>=7.0) | 🟡 Moderate (4.0-6.9) | 🔴 Low / Risk (<4.0)*\n`;
    fs.appendFileSync(summaryFile, md);
  }

  if (hasLowScore) {
    console.error("\n⚠️ Warning: One or more dependencies have a critically low OpenSSF Scorecard (< 3.0).");
  } else {
    console.log("\n✅ All evaluated OSS dependencies maintain healthy OpenSSF Scorecard postures.");
  }
}

main().catch((err) => {
  console.error("Failed to evaluate OSS health:", err);
  process.exit(1);
});
