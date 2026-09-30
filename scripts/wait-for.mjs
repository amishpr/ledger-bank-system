// Waits until every given localhost port accepts connections, then exits.
// `npm run dev` starts Postgres, Kafka, five services and the dashboard at
// once; this keeps each service from starting before what it needs is up.
//
//   node scripts/wait-for.mjs 5432 9092
import { connect } from "node:net";

const ports = process.argv.slice(2).map(Number);
const deadline = Date.now() + 5 * 60_000;

function isOpen(port) {
  return new Promise((resolve) => {
    const socket = connect({ host: "127.0.0.1", port });
    socket.once("connect", () => {
      socket.destroy();
      resolve(true);
    });
    socket.once("error", () => resolve(false));
  });
}

for (const port of ports) {
  while (!(await isOpen(port))) {
    if (Date.now() > deadline) {
      console.error(`Gave up waiting for port ${port}`);
      process.exit(1);
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
}
