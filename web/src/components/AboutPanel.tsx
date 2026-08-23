// The wording here is the README's opening description, kept in step with
// it on purpose: someone who finds the hosted dashboard first should read
// the same explanation as someone who finds the repository first.
export function AboutPanel() {
  return (
    <div className="about">
      <h2>About this project</h2>
      <p>
        Ledger is a small core banking system built to show how a real double entry ledger works under the hood. It has
        a Node and TypeScript API backed by a Prisma database, and a React dashboard that updates in real time as money
        moves between accounts.
      </p>
      <p>
        The project is not trying to be a full bank. It is trying to get the hard parts right: every transaction has to
        balance, money is never represented as a float, retried requests never double post, and nothing already posted
        is ever edited or deleted.
      </p>
    </div>
  );
}
