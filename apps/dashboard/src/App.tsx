import { useState } from 'react';

export const brands = [
  { id: 'cosmic-curiosity', name: 'Cosmic Curiosity', focus: 'Space & science' },
  { id: 'history-uncovered', name: 'History Uncovered', focus: 'History' },
];

export const approvalReminder = 'Every video will need your review before it can be published.';

export default function App() {
  const [brandId, setBrandId] = useState(brands[0].id);
  const brand = brands.find((item) => item.id === brandId)!;

  return (
    <main className="shell">
      <header className="topbar">
        <a className="wordmark" href="#home" aria-label="Media OS home"><span className="mark">M</span> MEDIA<span className="muted">OS</span></a>
        <span className="stage">LOCAL WORKSPACE <i /></span>
      </header>
      <section className="welcome">
        <div>
          <p className="eyebrow">CONTENT OPERATIONS / OVERVIEW</p>
          <h1>Your studio, <em>in orbit.</em></h1>
          <p className="subhead">A clear view of what’s in production and what’s next.</p>
        </div>
        <label className="brand-picker">ACTIVE BRAND
          <select value={brandId} onChange={(event) => setBrandId(event.target.value)} aria-label="Active brand">
            {brands.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
          </select>
        </label>
      </section>
      <section className="queue" aria-labelledby="queue-title">
        <div className="section-heading">
          <div><p className="eyebrow">YOUR WORKSPACE</p><h2 id="queue-title">Content queue</h2></div>
          <span className="count">00 <small>IN PRODUCTION</small></span>
        </div>
        <div className="empty-state">
          <div className="orbit" aria-hidden="true"><span>✦</span></div>
          <p className="eyebrow">{brand.focus.toUpperCase()} / READY WHEN YOU ARE</p>
          <h3>A fresh page.</h3>
          <p>Your next Short will take shape here. Start with an idea worth sharing.</p>
          <button type="button" onClick={() => window.alert('Short creation arrives in Stage 1.')}>＋ &nbsp;Create Short</button>
          <small className="approval-note">{approvalReminder}</small>
        </div>
      </section>
      <footer><span>MEDIA OS <b>•</b> STAGE 0</span><span>BUILT FOR HUMAN CREATIVITY</span></footer>
    </main>
  );
}
