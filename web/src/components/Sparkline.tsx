// A lightweight trend line for an account's recent running balance. Values
// are cent amounts converted to plain numbers purely to plot pixels, never
// used for anything that has to be exact.
export function Sparkline({ values }: { values: bigint[] }) {
  if (values.length < 2) {
    return <div className="sparkline sparkline-empty">Not enough history yet</div>;
  }

  const nums = values.map(Number);
  const min = Math.min(...nums);
  const max = Math.max(...nums);
  const range = max - min || 1;
  const width = 160;
  const height = 36;
  const pad = 3;

  const points = nums.map((n, i) => {
    const x = (i / (nums.length - 1)) * (width - pad * 2) + pad;
    const y = height - pad - ((n - min) / range) * (height - pad * 2);
    return [x, y] as const;
  });

  const path = points.map(([x, y], i) => `${i === 0 ? "M" : "L"}${x.toFixed(1)},${y.toFixed(1)}`).join(" ");
  const trendingUp = nums[nums.length - 1]! >= nums[0]!;

  return (
    <svg
      className={`sparkline ${trendingUp ? "sparkline-up" : "sparkline-down"}`}
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
    >
      <path d={path} fill="none" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx={points[points.length - 1]![0]} cy={points[points.length - 1]![1]} r="2.5" />
    </svg>
  );
}
