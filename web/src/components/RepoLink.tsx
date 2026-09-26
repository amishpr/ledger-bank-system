import { GithubLogo } from "@phosphor-icons/react";

const REPO_URL = "https://github.com/amishpr/ledger-bank-system";

// An <a>, not a <button>: it navigates, so it should get link behavior
// (open in a new tab, copy the address) and be announced as a link. The
// aria-label keeps a name on phones, where the visible label is hidden.
export function RepoLink() {
  return (
    <a
      className="btn btn-secondary repo-link"
      href={REPO_URL}
      target="_blank"
      rel="noopener noreferrer"
      aria-label="Source code on GitHub"
      title="View the source code on GitHub"
    >
      <GithubLogo size={16} aria-hidden />
      <span className="repo-link-label">GitHub</span>
    </a>
  );
}
