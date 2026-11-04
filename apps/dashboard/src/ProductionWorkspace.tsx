import { useEffect, useMemo, useState } from 'react';

type Brand = { id: string; name: string };
type Profile = { id: string; brandId: string | null; name: string; categoryId: string | null; contentFormat: string; settings: Record<string, string> };
type Content = { id: string; brandId: string; title: string; contentType: string; categoryId: string | null; productionProfileId: string | null; language: string; brief: string; description?: string; script?: string; tags?: string[]; targetChannelIds: string[]; sourceRecords: unknown[]; assets: unknown[]; platformMetadata?: Record<string, Record<string, string>> };
type Job = { id: string; contentId: string; mode: string; status: string; stage: string; productionProfileId: string; researchPacket: Record<string, unknown>; script: Record<string, unknown>; narration: Record<string, unknown>; visuals: Record<string, unknown>[]; providerExecutions: Record<string, unknown>[]; usage: Record<string, unknown>; warnings: string[]; errors: { message: string }[]; renderArtifactId: string | null };
type Catalog = { defaultMode: string; realProvidersEnabled: boolean; capabilities: { capability: string; providers: string[]; providerStatus?: Record<string, string> }[] };
const ACTIVE_STATUSES = ['QUEUED', 'RUNNING', 'RESEARCHING', 'SCRIPTING', 'GENERATING_AUDIO', 'GENERATING_VISUALS', 'RENDERING', 'QA_PENDING', 'CANCELLING'];

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { ...init, headers: { 'Content-Type': 'application/json', ...init?.headers } });
  if (!response.ok) { const error = await response.json().catch(() => ({})); throw new Error(error.message ?? error.error ?? `Request failed (${response.status})`); }
  return response.status === 204 ? undefined as T : response.json();
}

export default function ProductionWorkspace({ brands, profiles, content, onContentUpdated }: { brands: Brand[]; profiles: Profile[]; content: Content[]; onContentUpdated: () => Promise<void> }) {
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [selectedContent, setSelectedContent] = useState('');
  const [selectedProfile, setSelectedProfile] = useState('');
  const [job, setJob] = useState<Job | null>(null);
  const [scriptEdit, setScriptEdit] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const item = content.find((row) => row.id === selectedContent);
  const availableProfiles = useMemo(() => profiles.filter((profile) => item && (!profile.brandId || profile.brandId === item.brandId) && (!profile.categoryId || profile.categoryId === item.categoryId) && profile.contentFormat === item.contentType), [profiles, item]);
  const selectedProfileRecord = profiles.find((profile) => profile.id === selectedProfile);
  const profileWorkflow = useMemo(() => {
    try { return JSON.parse(selectedProfileRecord?.settings['mediaos.workflow_json'] ?? '{}') as { mode?: string; providers?: Record<string, string> }; }
    catch { return {}; }
  }, [selectedProfileRecord]);

  useEffect(() => { void request<Catalog>('/api/v1/providers').then(setCatalog).catch((e: Error) => setError(e.message)); }, []);
  useEffect(() => { if (item?.productionProfileId) setSelectedProfile(item.productionProfileId); else if (availableProfiles[0]) setSelectedProfile(availableProfiles[0].id); }, [item?.id, availableProfiles]);
  useEffect(() => {
    if (!job || !ACTIVE_STATUSES.includes(job.status)) return;
    const timer = window.setInterval(() => { void request<Job>(`/api/v1/production-jobs/${job.id}`).then(setJob).catch((e: Error) => setError(e.message)); }, 1500);
    return () => window.clearInterval(timer);
  }, [job?.id, job?.status]);
  useEffect(() => { setScriptEdit(String(job?.script?.narration_text ?? '')); }, [job?.id, job?.script?.narration_text]);

  async function start() {
    if (!item || !selectedProfile) return;
    setBusy(true); setError(''); setNotice('');
    try {
      const started = await request<Job>(`/api/v1/content-items/${item.id}/production-jobs`, { method: 'POST', body: JSON.stringify({ productionProfileId: selectedProfile, idempotencyKey: crypto.randomUUID() }) });
      setJob(started); setNotice(started.status === 'UNSUPPORTED_RENDERER' ? 'The selected profile has no compatible renderer.' : 'Production job started in local mock mode.');
    } catch (e) { setError(e instanceof Error ? e.message : 'Unable to start production'); } finally { setBusy(false); }
  }
  async function cancel() { if (!job) return; try { setJob(await request<Job>(`/api/v1/production-jobs/${job.id}`, { method: 'DELETE' })); } catch (e) { setError(e instanceof Error ? e.message : 'Unable to cancel'); } }
  async function saveScript() {
    if (!item || !job) return;
    setError(''); setNotice('');
    try {
      await request(`/api/v1/content-items/${item.id}`, { method: 'PUT', body: JSON.stringify({ brandId: item.brandId, categoryId: item.categoryId, title: String(job.script.title ?? item.title), contentType: item.contentType, language: item.language, brief: item.brief, productionProfileId: selectedProfile, targetChannelIds: item.targetChannelIds, sourceRecords: item.sourceRecords, assets: item.assets, description: item.description ?? '', script: scriptEdit, tags: item.tags ?? [], platformMetadata: item.platformMetadata ?? {} }) });
      await onContentUpdated();
      const rerender = await request<Job>(`/api/v1/content-items/${item.id}/production-jobs`, { method: 'POST', body: JSON.stringify({ productionProfileId: selectedProfile, idempotencyKey: crypto.randomUUID() }) });
      setJob(rerender); setNotice('Edited script saved. The previous render was detached and a new render is being produced from your script.');
    } catch (e) { setError(e instanceof Error ? e.message : 'Unable to save script'); }
  }

  return <section id="production" className="production-workspace">
    <div className="production-header"><div><p className="eyebrow">STAGE 4 / LOCAL PRODUCTION</p><h2>Production workspace</h2><p>Research, draft and assemble content with capability based providers. Mock output is synthetic and is never presented as real narration or factual verification.</p></div><span className="mode-pill">{job?.mode ?? profileWorkflow.mode ?? catalog?.defaultMode ?? 'MOCK'} MODE · REAL CALLS {catalog?.realProvidersEnabled ? 'ENABLED' : 'DISABLED'}</span></div>
    {(error || notice) && <div role="status" className={error ? 'flash error' : 'flash success'}>{error || notice}</div>}
    <div className="production-layout">
      <aside className="production-side panel"><p className="eyebrow">CREATE DRAFT</p>
        <label className="field"><span>Content item</span><select value={selectedContent} onChange={(e) => { setSelectedContent(e.target.value); setJob(null); }}><option value="">Choose content</option>{content.map((row) => <option key={row.id} value={row.id}>{row.title} · {row.contentType}</option>)}</select></label>
        {item && <><p className="production-context">{brands.find((brand) => brand.id === item.brandId)?.name ?? 'Brand'} · {item.contentType}</p>
          <label className="field"><span>Production profile</span><select value={selectedProfile} onChange={(e) => setSelectedProfile(e.target.value)}><option value="">Choose profile</option>{availableProfiles.map((profile) => <option key={profile.id} value={profile.id}>{profile.name}</option>)}</select></label>
          <button className="primary" disabled={!selectedProfile || busy || (!!job && ACTIVE_STATUSES.includes(job.status))} onClick={() => void start()}>{busy ? 'Starting…' : 'Start production'}</button>
        </>}
        <div className="provider-list"><p className="eyebrow">SELECTED CAPABILITY PROVIDERS</p>{catalog?.capabilities.map((entry) => {
          const provider = profileWorkflow.providers?.[entry.capability] ?? entry.providers[0];
          const state = entry.providerStatus?.[provider] ?? 'PROFILE SELECTED';
          return <div key={entry.capability}><span>{entry.capability.replaceAll('_', ' ')}</span><strong title={state}>{provider} <small>· {state.replaceAll('_', ' ').toLowerCase()}</small></strong></div>;
        })}</div>
      </aside>
      <section className="production-result panel" aria-live="polite">
        {!job ? <div className="empty-production"><span>✳</span><h3>One job, four capabilities</h3><p>Select a content item and profile. The local workflow organizes user supplied sources without live web research, drafts a script, generates clearly labeled synthetic test assets, renders through Stage 2 and queues the render for Stage 3 review.</p></div> : <>
          <div className="job-heading"><div><p className="eyebrow">JOB {job.id.slice(0, 8)}</p><h3>{job.status.replaceAll('_', ' ')}</h3></div><span className="stage-pill">{job.stage.replaceAll('_', ' ')}</span>{ACTIVE_STATUSES.includes(job.status) && job.status !== 'CANCELLING' && <button className="secondary" onClick={() => void cancel()}>Cancel</button>}</div>
          <div className="job-steps">{['RESEARCHING', 'SCRIPTING', 'GENERATING_AUDIO', 'GENERATING_VISUALS', 'RENDERING', 'READY_FOR_REVIEW'].map((stage) => <span key={stage} className={job.stage === stage ? 'current' : ''}>{stage.replaceAll('_', ' ')}</span>)}</div>
          {job.status === 'READY_FOR_REVIEW' && <div className="ready-callout">Render registered with Stage 3. Human review is required before approval or export. <a href="#review">Open review studio →</a></div>}
          {job.errors.map((entry, i) => <p className="flash error" key={i}>{entry.message}</p>)}{job.warnings.map((warning, i) => <p className="production-warning" key={i}>{warning}</p>)}
          {job.researchPacket && <section className="production-section"><h4>Research packet <small>source organization only · no live web research</small></h4><p>{String(job.researchPacket.summary ?? 'No research summary returned.')}</p><pre>{JSON.stringify(job.researchPacket.source_references ?? job.researchPacket.sources ?? [], null, 2)}</pre><p className="muted-copy">Claims that lack source evidence remain marked for verification. Source text is treated as untrusted input.</p></section>}
          {job.script && <section className="production-section"><h4>Script draft <small>mock generated</small></h4><label className="field"><span>Narration text</span><textarea rows={5} value={scriptEdit} onChange={(e) => setScriptEdit(e.target.value)} /></label><button className="secondary" onClick={() => void saveScript()}>Save script to content</button><pre>{JSON.stringify({ title: job.script.title, hook: job.script.hook, scenes: job.script.scenes, warnings: job.script.warnings }, null, 2)}</pre></section>}
          {job.narration && <section className="production-section"><h4>Narration asset <small>synthetic test tone · not speech</small></h4><audio controls src={`/api/v1/production-jobs/${job.id}/audio`} /><p>{String(job.narration.mime_type ?? 'audio/wav')} · {String(job.narration.duration_seconds ?? 'duration unavailable')} seconds</p></section>}
          {!!job.visuals.length && <section className="production-section"><h4>Visual assets <small>original synthetic test images</small></h4><div className="production-visuals">{job.visuals.map((visual) => <figure key={String(visual.asset_id)}><img src={`/api/v1/production-jobs/${job.id}/assets/${visual.asset_id}`} alt={String(visual.description ?? 'Synthetic test visual')} /><figcaption>{String(visual.asset_id)} · synthetic test</figcaption></figure>)}</div></section>}
          {!!job.providerExecutions.length && <section className="production-section"><h4>Provider execution</h4><pre>{JSON.stringify(job.providerExecutions, null, 2)}</pre><p>Reported usage/cost: <code>{JSON.stringify(job.usage)}</code>. Cost is shown only when a provider reports it or configured pricing supports calculation.</p></section>}
        </>}
      </section>
    </div>
  </section>;
}
