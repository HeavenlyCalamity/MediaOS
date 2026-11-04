import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { splitCommaList } from './domain';
import './review.css';

type Brand = { id: string; name: string };
type Category = { id: string; name: string; parentCategoryId: string | null };
type Destination = { id: string; platform: string; displayName: string };
type Source = { title: string; url: string; publisher: string; accessedAt: string };
type Asset = { kind: string; uri: string; mimeType: string; owner: string; licenseType: string; licenseEvidence: string; attribution: string; permittedUses: string[] };
type ContentItem = {
  id: string; brandId: string; categoryId: string | null; title: string; contentType: string; language: string; brief: string;
  description: string; script: string; tags: string[]; platformMetadata: Record<string, Record<string, string>>;
  productionProfileId: string | null; targetChannelIds: string[]; sourceRecords: Source[]; assets: Asset[]; status: string; version: number;
};
type Artifact = {
  id: string; contentId: string; brandId: string; originalFileName: string; sha256: string; uploadedAt: string;
  outputMetadata: Record<string, unknown>; checks: Record<string, boolean>; active: boolean;
  assetProvenance: { assetId: string; kind: string; sourceReference: string; rights: { owner?: string; license_type?: string; license_evidence?: string; attribution?: string; permitted_uses?: string[] } }[];
  subtitle: { fileName: string; burnedIn: boolean; settings: Record<string, unknown> }; warnings: string[];
};
type ReviewEvent = {
  eventId: string; reviewerIdentity: string; decision: string; note: string; createdAt: string; contentVersion: number;
  renderArtifactId: string; renderSha256: string; metadataSha256: string; invalidated: boolean; invalidatedAt: string | null; invalidationReason: string | null;
};
type Summary = {
  content: ContentItem; brandName: string; categoryName: string | null; reviewStatus: string; artifact: Artifact | null;
  approval: { valid: boolean; event: ReviewEvent | null; reason: string | null };
};

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  if (init?.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const response = await fetch(path, { ...init, headers });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error ?? `Request failed (${response.status})`);
  }
  return response.status === 204 ? undefined as T : response.json();
}

function safeHttpUrl(value: string): string | null {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.href : null;
  } catch { return null; }
}

function rightsIssues(asset: Asset): string[] {
  const issues: string[] = [];
  if (!asset.owner?.trim()) issues.push('owner missing');
  if (!asset.licenseType?.trim()) issues.push('license missing');
  if (!asset.permittedUses?.length) issues.push('permitted uses missing');
  if (asset.licenseType && asset.licenseType.toLowerCase() !== 'owned') {
    if (!asset.licenseEvidence?.trim()) issues.push('license evidence missing');
    if (!asset.attribution?.trim()) issues.push('attribution missing');
  }
  return issues;
}

export default function ReviewWorkspace({ brands, categories, onContentUpdated }: {
  brands: Brand[]; categories: Category[]; onContentUpdated: () => Promise<unknown>;
}) {
  const [queue, setQueue] = useState<Summary[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [destinations, setDestinations] = useState<Destination[]>([]);
  const [history, setHistory] = useState<ReviewEvent[]>([]);
  const [reviewer, setReviewer] = useState('');
  const [reviewNote, setReviewNote] = useState('');
  const [rejectionReason, setRejectionReason] = useState('');
  const [resubmitNote, setResubmitNote] = useState('');
  const [videoFile, setVideoFile] = useState<File | null>(null);
  const [manifestFile, setManifestFile] = useState<File | null>(null);
  const [qaFile, setQaFile] = useState<File | null>(null);
  const [edit, setEdit] = useState({ title: '', description: '', script: '', language: '', tags: '', platformMetadata: '{}' });
  const [targetChannelIds, setTargetChannelIds] = useState<string[]>([]);
  const [brandFilter, setBrandFilter] = useState('');
  const [categoryFilter, setCategoryFilter] = useState('');
  const [formatFilter, setFormatFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function loadQueue(keepSelection = true) {
    const rows = await api<Summary[]>('/api/v1/reviews/queue');
    setQueue(rows);
    const nextId = keepSelection && rows.some((row) => row.content.id === selectedId) ? selectedId : rows[0]?.content.id ?? '';
    setSelectedId(nextId);
  }

  useEffect(() => { void loadQueue(false).catch((reason: Error) => setError(reason.message)); }, []);

  const selected = queue.find((row) => row.content.id === selectedId) ?? null;
  const formats = useMemo(() => [...new Set(queue.map((row) => row.content.contentType))].sort(), [queue]);
  const filtered = queue.filter((row) => (!brandFilter || row.content.brandId === brandFilter)
    && (!categoryFilter || row.content.categoryId === categoryFilter)
    && (!formatFilter || row.content.contentType === formatFilter)
    && (!statusFilter || row.reviewStatus === statusFilter));

  useEffect(() => {
    if (!selected) { setDestinations([]); setHistory([]); return; }
    let alive = true;
    Promise.all([
      api<Destination[]>(`/api/v1/brands/${encodeURIComponent(selected.content.brandId)}/channels`),
      api<ReviewEvent[]>(`/api/v1/content-items/${encodeURIComponent(selected.content.id)}/review-history`),
    ]).then(([nextDestinations, nextHistory]) => {
      if (alive) { setDestinations(nextDestinations); setHistory(nextHistory); }
    }).catch((reason: Error) => { if (alive) setError(reason.message); });
    const content = selected.content;
    setEdit({ title: content.title ?? '', description: content.description ?? '', script: content.script ?? '', language: content.language ?? '',
      tags: (content.tags ?? []).join(', '), platformMetadata: JSON.stringify(content.platformMetadata ?? {}, null, 2) });
    setTargetChannelIds(content.targetChannelIds ?? []);
    return () => { alive = false; };
  }, [selectedId, selected?.content.version]);

  async function action(task: () => Promise<void>, message: string) {
    setError(''); setNotice(''); setBusy(true);
    try {
      await task();
      await loadQueue();
      if (selectedId) setHistory(await api<ReviewEvent[]>(`/api/v1/content-items/${encodeURIComponent(selectedId)}/review-history`));
      setNotice(message);
    }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Request failed'); }
    finally { setBusy(false); }
  }

  async function attachRender(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected || !videoFile || !manifestFile || !qaFile) { setError('Select the MP4, production manifest, and QA report.'); return; }
    const form = event.currentTarget;
    const data = new FormData(); data.append('video', videoFile); data.append('manifest', manifestFile); data.append('qaReport', qaFile);
    await action(async () => {
      await api(`/api/v1/content-items/${selected.content.id}/render-artifacts`, { method: 'POST', body: data });
      setVideoFile(null); setManifestFile(null); setQaFile(null); form.reset();
    }, 'Render attached and QA validated.');
  }

  async function saveMetadata(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected) return;
    let platformMetadata: Record<string, Record<string, string>>;
    try { platformMetadata = JSON.parse(edit.platformMetadata || '{}') as Record<string, Record<string, string>>; }
    catch { setError('Platform metadata must be valid JSON.'); return; }
    await action(async () => {
      const item = selected.content;
      await api(`/api/v1/content-items/${item.id}`, { method: 'PUT', body: JSON.stringify({
        brandId: item.brandId, categoryId: item.categoryId, title: edit.title, contentType: item.contentType,
        language: edit.language, brief: item.brief, productionProfileId: item.productionProfileId,
        targetChannelIds, sourceRecords: item.sourceRecords, assets: item.assets, description: edit.description,
        script: edit.script, tags: splitCommaList(edit.tags), platformMetadata,
      }) });
      await onContentUpdated();
    }, 'Metadata saved. Any prior approval is invalidated.');
  }

  async function approve() {
    if (!selected?.artifact || !selected.approval || selected.approval.valid) return;
    if (!window.confirm(`Approve “${selected.content.title}” version ${selected.content.version} with render ${selected.artifact.sha256.slice(0, 12)}…?`)) return;
    await action(async () => {
      await api(`/api/v1/content-items/${selected.content.id}/approve`, { method: 'POST', body: JSON.stringify({
        reviewerIdentity: reviewer, note: reviewNote, expectedContentVersion: selected.content.version, expectedArtifactId: selected.artifact!.id,
      }) });
    }, 'Approval recorded for this render and metadata version.');
  }

  async function reject() {
    if (!selected?.artifact) return;
    await action(async () => {
      await api(`/api/v1/content-items/${selected.content.id}/reject`, { method: 'POST', body: JSON.stringify({
        reviewerIdentity: reviewer, reason: rejectionReason, expectedContentVersion: selected.content.version, expectedArtifactId: selected.artifact!.id,
      }) });
      setRejectionReason('');
    }, 'Rejection recorded. Add changes and explicitly resubmit when ready.');
  }

  async function resubmit() {
    if (!selected) return;
    await action(async () => {
      await api(`/api/v1/content-items/${selected.content.id}/resubmit`, { method: 'POST', body: JSON.stringify({ reviewerIdentity: reviewer, note: resubmitNote }) });
      setResubmitNote('');
    }, 'Content returned to the review queue as a new version.');
  }

  return <section id="review" className="review-workspace">
    <div className="review-titlebar"><div><p className="eyebrow">CONTENT OPERATIONS / STAGE 3</p><h2>Review studio</h2><p>Inspect the render, metadata, sources, rights, and approval record before export.</p></div>
      <button className="secondary" type="button" onClick={() => void loadQueue().catch((reason: Error) => setError(reason.message))}>Refresh queue</button></div>
    {(error || notice) && <div className={`review-flash ${error ? 'error' : 'success'}`} role="status">{error || notice}</div>}
    <div className="review-layout">
      <aside className="review-queue panel">
        <div className="review-queue-heading"><div><p className="eyebrow">REVIEW QUEUE</p><h3>Content</h3></div><span className="badge">{filtered.length}</span></div>
        <div className="review-filters">
          <label>Brand<select value={brandFilter} onChange={(event) => setBrandFilter(event.target.value)}><option value="">All brands</option>{brands.map((brand) => <option key={brand.id} value={brand.id}>{brand.name}</option>)}</select></label>
          <label>Category<select value={categoryFilter} onChange={(event) => setCategoryFilter(event.target.value)}><option value="">All categories</option>{categories.map((category) => <option key={category.id} value={category.id}>{category.name}</option>)}</select></label>
          <label>Format<select value={formatFilter} onChange={(event) => setFormatFilter(event.target.value)}><option value="">All formats</option>{formats.map((format) => <option key={format} value={format}>{format}</option>)}</select></label>
          <label>Review status<select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}><option value="">All statuses</option>{['PENDING', 'APPROVED', 'REJECTED', 'INVALIDATED', 'NOT_READY'].map((status) => <option key={status}>{status}</option>)}</select></label>
        </div>
        <ul className="review-queue-list">{filtered.map((row) => <li key={row.content.id}>
          <button type="button" className={`queue-card ${selectedId === row.content.id ? 'selected' : ''}`} onClick={() => setSelectedId(row.content.id)}>
            <span className={`status-pill ${row.reviewStatus.toLowerCase()}`}>{row.reviewStatus}</span>
            <strong>{row.content.title}</strong><span>{row.brandName} · {row.content.contentType}</span>
            <small>{row.artifact ? `Render ${row.artifact.outputMetadata.width}×${row.artifact.outputMetadata.height} · ${row.artifact.active ? 'attached' : 'replaced'}` : 'No render attached'}</small>
          </button>
        </li>)}</ul>
        {filtered.length === 0 && <div className="review-empty">No items match these filters.</div>}
      </aside>

      <div className="review-detail">
        {!selected ? <div className="panel review-empty detail-empty"><h3>Select content to review</h3><p>Choose an item from the queue to attach its Stage 2 render or inspect an existing review.</p></div> : <>
          <section className="panel preview-panel">
            <div className="panel-heading"><div><p className="eyebrow">RENDER PREVIEW</p><h3>{selected.content.title}</h3></div><span className={`status-pill ${selected.reviewStatus.toLowerCase()}`}>{selected.reviewStatus}</span></div>
            {selected.artifact ? <div className="preview-grid">
              <video className="render-player" controls preload="metadata" src={`/api/v1/render-artifacts/${encodeURIComponent(selected.artifact.id)}/video`}>Your browser does not support video playback.</video>
              <div className="render-facts"><h4>Technical QA</h4><dl>
                <div><dt>Resolution</dt><dd>{String(selected.artifact.outputMetadata.width)} × {String(selected.artifact.outputMetadata.height)}</dd></div>
                <div><dt>Frame rate</dt><dd>{String(selected.artifact.outputMetadata.frameRate)} fps</dd></div>
                <div><dt>Duration</dt><dd>{Number(selected.artifact.outputMetadata.durationSeconds).toFixed(2)} sec</dd></div>
                <div><dt>Video / audio</dt><dd>{String(selected.artifact.outputMetadata.videoCodec)} / {String(selected.artifact.outputMetadata.audioCodec ?? 'none')}</dd></div>
                <div><dt>Pixel format</dt><dd>{String(selected.artifact.outputMetadata.pixelFormat)}</dd></div>
                <div><dt>SHA-256</dt><dd className="hash-value">{selected.artifact.sha256}</dd></div>
              </dl><div className="qa-checks"><strong>QA checks</strong>{Object.entries(selected.artifact.checks).map(([name, passed]) => <span key={name} className={passed ? 'check-pass' : 'check-fail'}>{passed ? '✓' : '×'} {name.replaceAll('_', ' ')}</span>)}</div></div>
            </div> : <div className="no-render">No render has been attached to this content item.</div>}
            {selected.artifact && <div className="stage2-qa-details">
              <div><h4>Subtitle burn-in</h4><p>{selected.artifact.subtitle?.burnedIn ? 'Included' : 'Not confirmed'} · {selected.artifact.subtitle?.fileName || 'Subtitle file not recorded'}</p>
                <pre>{JSON.stringify(selected.artifact.subtitle?.settings ?? {}, null, 2)}</pre></div>
              <div><h4>Render warnings</h4>{selected.artifact.warnings?.length ? <ul>{selected.artifact.warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul> : <p>No QA warnings reported.</p>}</div>
            </div>}
            <form className="artifact-form" onSubmit={(event) => void attachRender(event)}>
              <p>Attach a completed Stage 2 render. The API verifies its manifest IDs, QA checks, actual file checksum, and FFprobe metadata.</p>
              <div className="artifact-files"><label>Rendered MP4<input type="file" accept="video/mp4,.mp4" onChange={(event) => setVideoFile(event.target.files?.[0] ?? null)} required /></label>
                <label>Production manifest JSON<input type="file" accept="application/json,.json" onChange={(event) => setManifestFile(event.target.files?.[0] ?? null)} required /></label>
                <label>QA report JSON<input type="file" accept="application/json,.json" onChange={(event) => setQaFile(event.target.files?.[0] ?? null)} required /></label></div>
              <button className="secondary" type="submit" disabled={busy}>Attach render and validate QA</button>
            </form>
          </section>

          <section className="panel content-editor-panel">
            <div className="panel-heading"><div><p className="eyebrow">EDITORIAL REVIEW</p><h3>Content and publishing metadata</h3></div><span className="version-tag">CONTENT v{selected.content.version}</span></div>
            <form className="review-edit-form" onSubmit={(event) => void saveMetadata(event)}>
              <label>Title<input value={edit.title} onChange={(event) => setEdit({ ...edit, title: event.target.value })} required /></label>
              <label>Description<textarea rows={3} value={edit.description} onChange={(event) => setEdit({ ...edit, description: event.target.value })} required /></label>
              <label>Script<textarea rows={6} value={edit.script} onChange={(event) => setEdit({ ...edit, script: event.target.value })} /></label>
              <div className="review-edit-row"><label>Language<input value={edit.language} onChange={(event) => setEdit({ ...edit, language: event.target.value })} required /></label>
                <label>Tags (comma separated)<input value={edit.tags} onChange={(event) => setEdit({ ...edit, tags: event.target.value })} /></label></div>
              <fieldset className="destination-picker"><legend>Target platform destinations</legend>
                {destinations.length === 0 ? <p>No destinations configured for this brand.</p> : destinations.map((destination) => <label key={destination.id}><input type="checkbox" checked={targetChannelIds.includes(destination.id)} onChange={(event) => setTargetChannelIds(event.target.checked ? [...targetChannelIds, destination.id] : targetChannelIds.filter((id) => id !== destination.id))} /> {destination.displayName} <small>{destination.platform}</small></label>)}</fieldset>
              <label>Platform-specific metadata (JSON by platform)<textarea rows={4} spellCheck={false} value={edit.platformMetadata} onChange={(event) => setEdit({ ...edit, platformMetadata: event.target.value })} /></label>
              <button className="secondary" disabled={busy} type="submit">Save metadata and invalidate previous approval</button>
            </form>
            <div className="source-rights-grid">
              <section><h4>Research sources <span>{selected.content.sourceRecords.length}</span></h4>
                {selected.content.sourceRecords.length ? selected.content.sourceRecords.map((source, index) => {
                  const href = safeHttpUrl(source.url);
                  return <article className="source-card" key={`${source.url}-${index}`}><strong>{source.title || 'Untitled source'}</strong>
                    <small>{source.publisher || 'Publisher not recorded'}{source.accessedAt ? ` · Accessed ${source.accessedAt}` : ''}</small>
                    {href ? <a href={href} target="_blank" rel="noopener noreferrer">{source.url}</a> : <span className="invalid-link">Invalid or unsupported source URL: {source.url}</span>}</article>;
                }) : <p className="muted-copy">No source records are attached.</p>}
              </section>
              <section><h4>Asset rights <span>{selected.content.assets.length}</span></h4>
                {selected.content.assets.length ? selected.content.assets.map((asset, index) => {
                  const issues = rightsIssues(asset);
                  return <article className={`rights-card ${issues.length ? 'rights-flagged' : ''}`} key={`${asset.uri}-${index}`}>
                    <strong>{asset.uri || asset.kind || 'Unnamed asset'}</strong><small>{asset.kind || 'Type not recorded'} · {asset.mimeType || 'MIME type not recorded'}</small>
                    <p>Owner: {asset.owner || 'Missing'} · License: {asset.licenseType || 'Missing'}</p>
                    {asset.licenseEvidence && <p>Evidence: {asset.licenseEvidence}</p>}{asset.attribution && <p>Attribution: {asset.attribution}</p>}
                    <p>Permitted uses: {asset.permittedUses?.join(', ') || 'Missing'}</p>
                    {issues.length > 0 && <strong className="rights-warning">Rights metadata needs attention: {issues.join(', ')}</strong>}
                  </article>;
                }) : <p className="muted-copy">No assets or rights records are attached.</p>}
                {selected.artifact?.assetProvenance?.length ? <div className="render-rights-list"><h5>Stage 2 render provenance</h5>{selected.artifact.assetProvenance.map((asset) => {
                  const rights = asset.rights ?? {};
                  const linkedRecord = selected.content.assets.find((candidate) => candidate.uri === asset.sourceReference);
                  const linkedMismatch = !!linkedRecord && (linkedRecord.owner !== rights.owner || linkedRecord.licenseType !== rights.license_type
                    || linkedRecord.licenseEvidence !== (rights.license_evidence ?? '') || linkedRecord.attribution !== (rights.attribution ?? '')
                    || [...linkedRecord.permittedUses].sort().join('|') !== [...(rights.permitted_uses ?? [])].sort().join('|'));
                  const missing = [!rights.owner?.trim() && 'owner missing', !rights.license_type?.trim() && 'license missing',
                    !rights.permitted_uses?.length && 'permitted uses missing', rights.license_type && rights.license_type.toLowerCase() !== 'owned' && !rights.license_evidence?.trim() && 'license evidence missing',
                    rights.license_type && rights.license_type.toLowerCase() !== 'owned' && !rights.attribution?.trim() && 'attribution missing',
                    linkedMismatch && 'rights conflict with the matching content asset record'].filter(Boolean) as string[];
                  return <article className={`rights-card ${missing.length ? 'rights-flagged' : ''}`} key={asset.assetId}>
                    <strong>{asset.assetId} · {asset.kind}</strong><small>{asset.sourceReference}</small><p>Owner: {rights.owner || 'Missing'} · License: {rights.license_type || 'Missing'}</p>
                    {rights.license_evidence && <p>Evidence: {rights.license_evidence}</p>}{rights.attribution && <p>Attribution: {rights.attribution}</p>}
                    <p>Permitted uses: {rights.permitted_uses?.join(', ') || 'Missing'}</p>
                    {missing.length > 0 && <strong className="rights-warning">Manifest rights need attention: {missing.join(', ')}</strong>}
                  </article>;
                })}</div> : null}
              </section>
            </div>
          </section>

          <section className="panel decision-panel">
            <div className="panel-heading"><div><p className="eyebrow">HUMAN DECISION</p><h3>Review and record</h3></div><span className={`status-pill ${selected.reviewStatus.toLowerCase()}`}>{selected.reviewStatus}</span></div>
            {selected.approval.valid && <p className="approval-valid">Current approval is valid for render {selected.approval.event?.renderSha256.slice(0, 16)}… and metadata {selected.approval.event?.metadataSha256.slice(0, 16)}…</p>}
            {selected.approval.event?.invalidated && <p className="approval-invalid">Previous approval invalidated: {selected.approval.event.invalidationReason}</p>}
            <div className="reviewer-row"><label>Reviewer identity<input value={reviewer} onChange={(event) => setReviewer(event.target.value)} placeholder="Your name" required /></label>
              <label>Review note<input value={reviewNote} onChange={(event) => setReviewNote(event.target.value)} placeholder="Optional approval note" /></label></div>
            {selected.reviewStatus !== 'APPROVED' && selected.reviewStatus !== 'REJECTED' && <div className="decision-controls">
              <button className="approve-button" type="button" disabled={busy || !selected.artifact || !reviewer.trim()} onClick={() => void approve()}>Approve this version</button>
              <label>Rejection reason<input value={rejectionReason} onChange={(event) => setRejectionReason(event.target.value)} placeholder="Required to reject" /></label>
              <button className="reject-button" type="button" disabled={busy || !selected.artifact || !reviewer.trim() || !rejectionReason.trim()} onClick={() => void reject()}>Reject with reason</button>
            </div>}
            {selected.reviewStatus === 'REJECTED' && <div className="resubmit-controls"><p>Rejection is retained in history. Edit any requested details, then explicitly resubmit as a new content version.</p>
              <label>Resubmission note<input value={resubmitNote} onChange={(event) => setResubmitNote(event.target.value)} required /></label>
              <button className="secondary" type="button" disabled={busy || !reviewer.trim() || !resubmitNote.trim()} onClick={() => void resubmit()}>Resubmit for review</button></div>}
            <div className="export-controls"><a className={`export-link ${!selected.approval.valid ? 'disabled' : ''}`} href={selected.approval.valid ? `/api/v1/content-items/${selected.content.id}/export` : undefined} aria-disabled={!selected.approval.valid}>Download approved ZIP</a>
              <a className={`export-link ${!selected.approval.valid ? 'disabled' : ''}`} href={selected.approval.valid ? `/api/v1/content-items/${selected.content.id}/export/video` : undefined} aria-disabled={!selected.approval.valid}>Download approved MP4</a></div>
            <div className="history-heading"><h4>Review history</h4><span>{history.length} events · persisted locally</span></div>
            {history.length ? <ol className="history-list">{history.map((event) => <li key={event.eventId}>
              <span className={`history-dot ${event.decision.toLowerCase()}`} /><div><strong>{event.decision}{event.invalidated ? ' · INVALIDATED' : ''}</strong>
                <small>{event.reviewerIdentity} · v{event.contentVersion} · {new Date(event.createdAt).toLocaleString()}</small>
                {event.note && <p>{event.note}</p>}{event.invalidationReason && <p className="rights-warning">{event.invalidationReason}</p>}
                <small>Render SHA-256 {event.renderSha256} · Metadata SHA-256 {event.metadataSha256}</small></div>
            </li>)}</ol> : <p className="muted-copy">No review decisions have been recorded.</p>}
          </section>
        </>}
      </div>
    </div>
  </section>;
}
