import { useCallback, useEffect, useState } from 'react';
import { approvalReminder, splitCommaList } from './domain';
import ReviewWorkspace from './ReviewWorkspace';

type Brand = { id: string; name: string; slug: string; description: string; defaultLanguage: string; tone: string; contentPillars: string[]; audienceProfile: Record<string, string> };
type Category = { id: string; name: string; slug: string; description: string; parentCategoryId: string | null };
type Channel = { id: string; brandId: string; platform: string; displayName: string; accountReference: string; status: string };
type Profile = { id: string; brandId: string | null; name: string; categoryId: string | null; contentFormat: string; workflowStages: string[]; settings: Record<string, string> };
type Source = { title: string; url: string; publisher: string; accessedAt: string };
type Asset = { kind: string; uri: string; mimeType: string; owner: string; licenseType: string; licenseEvidence: string; attribution: string; permittedUses: string[] };
type ContentItem = { id: string; brandId: string; categoryId: string | null; title: string; contentType: string; language: string; brief: string; productionProfileId: string | null; targetChannelIds: string[]; sourceRecords: Source[]; assets: Asset[]; status: string; version: number };

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error ?? `Request failed (${response.status})`);
  }
  return response.status === 204 ? undefined as T : response.json();
}

const blankBrand = { name: '', slug: '', description: '', defaultLanguage: 'en', tone: '', pillars: '', audience: '' };
const blankCategory = { name: '', slug: '', description: '', parentCategoryId: '' };

export default function App() {
  const [brands, setBrands] = useState<Brand[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [channels, setChannels] = useState<Channel[]>([]);
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [content, setContent] = useState<ContentItem[]>([]);
  const [brandId, setBrandId] = useState('');
  const [editingBrandId, setEditingBrandId] = useState('');
  const [brandForm, setBrandForm] = useState(blankBrand);
  const [editingCategoryId, setEditingCategoryId] = useState('');
  const [categoryForm, setCategoryForm] = useState(blankCategory);
  const [editingChannelId, setEditingChannelId] = useState('');
  const [editingProfileId, setEditingProfileId] = useState('');
  const [editingContentId, setEditingContentId] = useState('');
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');

  const [channelPlatform, setChannelPlatform] = useState('YouTube');
  const [channelName, setChannelName] = useState('');
  const [channelReference, setChannelReference] = useState('');
  const [profileName, setProfileName] = useState('');
  const [profileFormat, setProfileFormat] = useState('short-form video');
  const [profileStages, setProfileStages] = useState('brief, research, script, production, review');
  const [profileCategoryId, setProfileCategoryId] = useState('');

  const [title, setTitle] = useState('');
  const [contentType, setContentType] = useState('short-form video');
  const [language, setLanguage] = useState('en');
  const [brief, setBrief] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [profileId, setProfileId] = useState('');
  const [targetChannelIds, setTargetChannelIds] = useState<string[]>([]);
  const [sourceTitle, setSourceTitle] = useState('');
  const [sourceUrl, setSourceUrl] = useState('');
  const [extraSources, setExtraSources] = useState<Source[]>([]);
  const [assetKind, setAssetKind] = useState('');
  const [assetUri, setAssetUri] = useState('');
  const [assetOwner, setAssetOwner] = useState('');
  const [assetLicense, setAssetLicense] = useState('');
  const [assetLicenseEvidence, setAssetLicenseEvidence] = useState('');
  const [assetAttribution, setAssetAttribution] = useState('');
  const [assetUses, setAssetUses] = useState('');
  const [extraAssets, setExtraAssets] = useState<Asset[]>([]);

  const refresh = useCallback(async () => {
    const [nextBrands, nextCategories] = await Promise.all([
      api<Brand[]>('/api/v1/brands'), api<Category[]>('/api/v1/categories'),
    ]);
    setBrands(nextBrands);
    setCategories(nextCategories);
    const selected = nextBrands.some((brand) => brand.id === brandId) ? brandId : nextBrands[0]?.id ?? '';
    setBrandId(selected);
    const [nextContent, nextProfiles, nextChannels] = await Promise.all([
      api<ContentItem[]>(selected ? `/api/v1/content-items?brandId=${encodeURIComponent(selected)}` : '/api/v1/content-items'),
      api<Profile[]>(selected ? `/api/v1/production-profiles?brandId=${encodeURIComponent(selected)}` : '/api/v1/production-profiles'),
      selected ? api<Channel[]>(`/api/v1/brands/${encodeURIComponent(selected)}/channels`) : Promise.resolve([]),
    ]);
    setContent(nextContent);
    setProfiles(nextProfiles);
    setChannels(nextChannels);
  }, [brandId]);

  useEffect(() => { void refresh().catch((reason: Error) => setError(`API unavailable: ${reason.message}`)); }, [refresh]);

  async function run(action: () => Promise<void>, success: string) {
    setError(''); setNotice('');
    try { await action(); setNotice(success); await refresh(); }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Request failed'); }
  }

  function resetContentForm() {
    setEditingContentId(''); setTitle(''); setContentType('short-form video'); setLanguage('en'); setBrief('');
    setCategoryId(''); setProfileId(''); setTargetChannelIds([]); setSourceTitle(''); setSourceUrl('');
    setExtraSources([]); setAssetKind(''); setAssetUri(''); setAssetOwner(''); setAssetLicense(''); setAssetLicenseEvidence(''); setAssetAttribution(''); setAssetUses(''); setExtraAssets([]);
  }

  function editBrand(brand: Brand) {
    setEditingBrandId(brand.id);
    setBrandForm({ name: brand.name, slug: brand.slug, description: brand.description, defaultLanguage: brand.defaultLanguage,
      tone: brand.tone, pillars: brand.contentPillars.join(', '), audience: brand.audienceProfile.primaryAudience ?? '' });
  }

  function editCategory(category: Category) {
    setEditingCategoryId(category.id);
    setCategoryForm({ name: category.name, slug: category.slug, description: category.description, parentCategoryId: category.parentCategoryId ?? '' });
  }

  function editChannel(channel: Channel) {
    setEditingChannelId(channel.id); setChannelPlatform(channel.platform); setChannelName(channel.displayName); setChannelReference(channel.accountReference);
  }

  function editProfile(profile: Profile) {
    setEditingProfileId(profile.id); setProfileName(profile.name); setProfileFormat(profile.contentFormat);
    setProfileCategoryId(profile.categoryId ?? ''); setProfileStages(profile.workflowStages.join(', '));
  }

  function editContent(item: ContentItem) {
    setEditingContentId(item.id); setTitle(item.title); setContentType(item.contentType); setLanguage(item.language); setBrief(item.brief);
    setCategoryId(item.categoryId ?? ''); setProfileId(item.productionProfileId ?? ''); setTargetChannelIds(item.targetChannelIds);
    setSourceTitle(item.sourceRecords[0]?.title ?? ''); setSourceUrl(item.sourceRecords[0]?.url ?? '');
    setExtraSources(item.sourceRecords.slice(1)); setExtraAssets(item.assets.slice(1));
    const asset = item.assets[0];
    setAssetKind(asset?.kind ?? ''); setAssetUri(asset?.uri ?? ''); setAssetOwner(asset?.owner ?? '');
    setAssetLicense(asset?.licenseType ?? ''); setAssetLicenseEvidence(asset?.licenseEvidence ?? '');
    setAssetAttribution(asset?.attribution ?? ''); setAssetUses(asset?.permittedUses.join(', ') ?? '');
  }

  const sourceRecords: Source[] = sourceUrl.trim() ? [{ title: sourceTitle, url: sourceUrl, publisher: '', accessedAt: new Date().toISOString() }] : [];
  const assets: Asset[] = assetKind.trim() || assetLicense.trim() ? [{ kind: assetKind, uri: assetUri, mimeType: '', owner: assetOwner,
    licenseType: assetLicense, licenseEvidence: assetLicenseEvidence, attribution: assetAttribution, permittedUses: splitCommaList(assetUses) }] : [];

  return (
    <main className="app-shell">
      <header className="topbar">
        <a className="wordmark" href="#home"><span className="mark">M</span> MEDIA<span className="muted">OS</span></a>
        <nav className="workspace-nav" aria-label="Workspace navigation"><a href="#review">Review studio</a><a href="#management">Configure</a></nav>
        <span className="stage">LOCAL WORKSPACE <i /></span>
      </header>
      <section className="intro">
        <div><p className="eyebrow">CONTENT OPERATIONS / STAGE 1</p><h1>Build your <em>media system.</em></h1>
          <p className="subhead">Create brands and workflows for any category, format, or audience.</p></div>
        <label className="brand-picker">ACTIVE BRAND<select value={brandId} onChange={(event) => setBrandId(event.target.value)}>
          {brands.length === 0 && <option value="">No brands yet</option>}{brands.map((brand) => <option key={brand.id} value={brand.id}>{brand.name}</option>)}
        </select></label>
      </section>
      {(error || notice) && <div className={error ? 'flash error' : 'flash success'} role="status">{error || notice}</div>}

      <ReviewWorkspace brands={brands} categories={categories} onContentUpdated={refresh} />

      <div id="management" className="management-grid">
        <section className="panel"><div className="panel-heading"><div><p className="eyebrow">CONFIGURATION</p><h2>Brands</h2></div><span className="badge">{brands.length}</span></div>
          <form onSubmit={(event) => { event.preventDefault(); void run(async () => {
            const payload = { name: brandForm.name, slug: brandForm.slug, description: brandForm.description, defaultLanguage: brandForm.defaultLanguage,
              tone: brandForm.tone, contentPillars: splitCommaList(brandForm.pillars), audienceProfile: brandForm.audience ? { primaryAudience: brandForm.audience } : {} };
            await api(editingBrandId ? `/api/v1/brands/${editingBrandId}` : '/api/v1/brands', { method: editingBrandId ? 'PUT' : 'POST', body: JSON.stringify(payload) });
            setBrandForm(blankBrand); setEditingBrandId('');
          }, editingBrandId ? 'Brand updated.' : 'Brand created.'); }}>
            <div className="form-row"><Field label="Brand name" value={brandForm.name} onChange={(v) => setBrandForm({ ...brandForm, name: v })} required />
              <Field label="Unique slug" value={brandForm.slug} onChange={(v) => setBrandForm({ ...brandForm, slug: v })} required /></div>
            <Field label="Description" value={brandForm.description} onChange={(v) => setBrandForm({ ...brandForm, description: v })} />
            <div className="form-row"><Field label="Language" value={brandForm.defaultLanguage} onChange={(v) => setBrandForm({ ...brandForm, defaultLanguage: v })} />
              <Field label="Tone" value={brandForm.tone} onChange={(v) => setBrandForm({ ...brandForm, tone: v })} /></div>
            <Field label="Content pillars (comma separated)" value={brandForm.pillars} onChange={(v) => setBrandForm({ ...brandForm, pillars: v })} />
            <Field label="Primary audience" value={brandForm.audience} onChange={(v) => setBrandForm({ ...brandForm, audience: v })} />
            <div className="form-actions"><button className="primary" type="submit">{editingBrandId ? 'Save brand' : 'Create brand'}</button>{editingBrandId && <button type="button" className="secondary" onClick={() => { setEditingBrandId(''); setBrandForm(blankBrand); }}>Cancel edit</button>}</div>
          </form>
          <ul className="record-list">{brands.map((brand) => <li key={brand.id}><div><strong>{brand.name}</strong><small>{brand.slug}{brand.contentPillars.length ? ` · ${brand.contentPillars.join(', ')}` : ''}</small></div><button className="text-button" onClick={() => editBrand(brand)}>Edit</button><button className="text-button danger-text" onClick={() => { if (window.confirm(`Delete ${brand.name}?`)) void run(async () => { await api(`/api/v1/brands/${brand.id}`, { method: 'DELETE' }); }, 'Brand deleted.'); }}>Delete</button></li>)}</ul>
        </section>

        <section className="panel"><div className="panel-heading"><div><p className="eyebrow">TAXONOMY</p><h2>Categories</h2></div><span className="badge">{categories.length}</span></div>
          <form onSubmit={(event) => { event.preventDefault(); void run(async () => {
            const payload = { ...categoryForm, parentCategoryId: categoryForm.parentCategoryId || null };
            await api(editingCategoryId ? `/api/v1/categories/${editingCategoryId}` : '/api/v1/categories', { method: editingCategoryId ? 'PUT' : 'POST', body: JSON.stringify(payload) });
            setCategoryForm(blankCategory); setEditingCategoryId('');
          }, editingCategoryId ? 'Category updated.' : 'Category created.'); }}>
            <div className="form-row"><Field label="Name" value={categoryForm.name} onChange={(v) => setCategoryForm({ ...categoryForm, name: v })} required />
              <Field label="Slug" value={categoryForm.slug} onChange={(v) => setCategoryForm({ ...categoryForm, slug: v })} required /></div>
            <Field label="Description" value={categoryForm.description} onChange={(v) => setCategoryForm({ ...categoryForm, description: v })} />
            <label className="field"><span>Parent category</span><select value={categoryForm.parentCategoryId} onChange={(e) => setCategoryForm({ ...categoryForm, parentCategoryId: e.target.value })}><option value="">Top level</option>{categories.filter((item) => item.id !== editingCategoryId).map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
            <div className="form-actions"><button className="primary" type="submit">{editingCategoryId ? 'Save category' : 'Create category'}</button>{editingCategoryId && <button type="button" className="secondary" onClick={() => { setEditingCategoryId(''); setCategoryForm(blankCategory); }}>Cancel edit</button>}</div>
          </form>
          <ul className="record-list">{categories.map((category) => <li key={category.id}><div><strong>{category.parentCategoryId ? '↳ ' : ''}{category.name}</strong><small>{category.slug}</small></div><button className="text-button" onClick={() => editCategory(category)}>Edit</button><button className="text-button danger-text" onClick={() => { if (window.confirm(`Delete ${category.name}?`)) void run(async () => { await api(`/api/v1/categories/${category.id}`, { method: 'DELETE' }); }, 'Category deleted.'); }}>Delete</button></li>)}</ul>
        </section>

        <section className="panel"><div className="panel-heading"><div><p className="eyebrow">DESTINATIONS</p><h2>Channels</h2></div><span className="badge">{channels.length}</span></div>
          {!brandId ? <p className="muted-copy">Create or select a brand to manage destinations.</p> : <>
            <form onSubmit={(event) => { event.preventDefault(); void run(async () => {
              await api(editingChannelId ? `/api/v1/channels/${editingChannelId}` : '/api/v1/channels', { method: editingChannelId ? 'PUT' : 'POST', body: JSON.stringify({ brandId, platform: channelPlatform, displayName: channelName, accountReference: channelReference, status: 'NOT_CONNECTED' }) });
              setEditingChannelId(''); setChannelName(''); setChannelReference('');
            }, editingChannelId ? 'Destination updated.' : 'Destination added. No account was connected.'); }}>
              <div className="form-row"><Field label="Platform" value={channelPlatform} onChange={setChannelPlatform} required /><Field label="Display name" value={channelName} onChange={setChannelName} required /></div>
              <Field label="Account reference (optional)" value={channelReference} onChange={setChannelReference} />
              <div className="form-actions"><button className="primary" type="submit">{editingChannelId ? 'Save destination' : 'Add destination'}</button>{editingChannelId && <button type="button" className="secondary" onClick={() => setEditingChannelId('')}>Cancel edit</button>}</div>
            </form>
            <ul className="record-list">{channels.map((channel) => <li key={channel.id}><div><strong>{channel.displayName}</strong><small>{channel.platform} · {channel.status}{channel.accountReference ? ` · ${channel.accountReference}` : ''}</small></div><button className="text-button" onClick={() => editChannel(channel)}>Edit</button></li>)}</ul>
          </>}
        </section>

        <section className="panel"><div className="panel-heading"><div><p className="eyebrow">WORKFLOW DESIGN</p><h2>Production profiles</h2></div><span className="badge">{profiles.length}</span></div>
          {!brandId ? <p className="muted-copy">Create or select a brand to configure workflows.</p> : <>
            <form onSubmit={(event) => { event.preventDefault(); void run(async () => {
              await api(editingProfileId ? `/api/v1/production-profiles/${editingProfileId}` : '/api/v1/production-profiles', { method: editingProfileId ? 'PUT' : 'POST', body: JSON.stringify({ brandId, name: profileName, categoryId: profileCategoryId || null, contentFormat: profileFormat, workflowStages: splitCommaList(profileStages), settings: {} }) });
              setEditingProfileId(''); setProfileName('');
            }, editingProfileId ? 'Production profile updated.' : 'Production profile created.'); }}>
              <Field label="Profile name" value={profileName} onChange={setProfileName} required />
              <div className="form-row"><Field label="Content format" value={profileFormat} onChange={setProfileFormat} required /><SelectField label="Category (optional)" value={profileCategoryId} onChange={setProfileCategoryId} options={categories.map((c) => [c.id, c.name] as [string, string])} /></div>
              <Field label="Workflow stages (comma separated)" value={profileStages} onChange={setProfileStages} />
              <div className="form-actions"><button className="primary" type="submit">{editingProfileId ? 'Save profile' : 'Add profile'}</button>{editingProfileId && <button type="button" className="secondary" onClick={() => setEditingProfileId('')}>Cancel edit</button>}</div>
            </form>
            <ul className="record-list">{profiles.map((profile) => <li key={profile.id}><div><strong>{profile.name}</strong><small>{profile.contentFormat}{profile.categoryId ? ` · ${categories.find((item) => item.id === profile.categoryId)?.name ?? 'Category'}` : ' · General'} · {profile.workflowStages.join(' → ')}</small></div><button className="text-button" onClick={() => editProfile(profile)}>Edit</button></li>)}</ul>
          </>}
        </section>
      </div>

      <section className="panel content-panel"><div className="panel-heading"><div><p className="eyebrow">CONTENT WORKSPACE</p><h2>{editingContentId ? 'Update content item' : 'Create content item'}</h2></div><span className="badge">{content.length} ITEMS</span></div>
        {!brandId ? <div className="empty-state"><h3>Create a brand to begin.</h3><p>Content belongs to a user-managed brand and can use any category or format.</p></div> : <form onSubmit={(event) => { event.preventDefault(); void run(async () => {
          const payload = { brandId, categoryId: categoryId || null, title, contentType, language, brief, productionProfileId: profileId || null, targetChannelIds, sourceRecords: [...sourceRecords, ...extraSources], assets: [...assets, ...extraAssets] };
          await api(editingContentId ? `/api/v1/content-items/${editingContentId}` : '/api/v1/content-items', { method: editingContentId ? 'PUT' : 'POST', body: JSON.stringify(payload) });
          resetContentForm();
        }, editingContentId ? 'Content item updated.' : 'Content item created in IDEA.'); }}>
          <div className="form-row three"><Field label="Title" value={title} onChange={setTitle} required /><Field label="Content type / format" value={contentType} onChange={setContentType} required /><Field label="Language" value={language} onChange={setLanguage} required /></div>
          <div className="form-row"><SelectField label="Category" value={categoryId} onChange={setCategoryId} options={categories.map((item) => [item.id, item.name] as [string, string])} /><SelectField label="Production profile" value={profileId} onChange={setProfileId} options={profiles.filter((item) => !item.categoryId || item.categoryId === categoryId).map((item) => [item.id, item.name] as [string, string])} /></div>
          <label className="field"><span>Brief</span><textarea rows={3} value={brief} onChange={(event) => setBrief(event.target.value)} /></label>
          {channels.length > 0 && <fieldset className="targets"><legend>Platform targets</legend>{channels.map((channel) => <label key={channel.id}><input type="checkbox" checked={targetChannelIds.includes(channel.id)} onChange={(event) => setTargetChannelIds(event.target.checked ? [...targetChannelIds, channel.id] : targetChannelIds.filter((id) => id !== channel.id))} /> {channel.displayName} <small>{channel.platform}</small></label>)}</fieldset>}
          <details className="metadata"><summary>Sources and asset rights metadata</summary><div className="form-row"><Field label="Source title" value={sourceTitle} onChange={setSourceTitle} /><Field label="Source URL" value={sourceUrl} onChange={setSourceUrl} /></div>
            <div className="form-row three"><Field label="Asset type" value={assetKind} onChange={setAssetKind} placeholder="music, gameplay, visual…" /><Field label="Asset URI" value={assetUri} onChange={setAssetUri} /><Field label="Owner" value={assetOwner} onChange={setAssetOwner} /></div>
            <div className="form-row"><Field label="License" value={assetLicense} onChange={setAssetLicense} placeholder="owned, CC BY…" /><Field label="License evidence" value={assetLicenseEvidence} onChange={setAssetLicenseEvidence} placeholder="URL or rights record" /></div>
            <div className="form-row"><Field label="Attribution" value={assetAttribution} onChange={setAssetAttribution} /><Field label="Permitted uses (comma separated)" value={assetUses} onChange={setAssetUses} placeholder="social, commercial…" /></div>
          </details>
          <div className="form-actions"><button className="primary" type="submit">{editingContentId ? 'Save content' : 'Create content item'}</button>{editingContentId && <button type="button" className="secondary" onClick={resetContentForm}>Cancel edit</button>}</div>
        </form>}
        <div className="queue-heading"><h3>Content queue</h3><span>{approvalReminder}</span></div>
        {content.length === 0 ? <div className="empty-state compact"><h3>No content items yet.</h3><p>Create a content item to start organizing work for this brand.</p></div> : <div className="table-wrap"><table><thead><tr><th>Title</th><th>Category</th><th>Format</th><th>Status</th><th>Rights / sources</th><th></th></tr></thead><tbody>{content.map((item) => <tr key={item.id}><td><strong>{item.title}</strong><small>v{item.version} · {item.language}</small></td><td>{categories.find((category) => category.id === item.categoryId)?.name ?? '—'}</td><td>{item.contentType}</td><td><span className="status">{item.status}</span></td><td>{item.assets.length} assets · {item.sourceRecords.length} sources</td><td><button className="text-button" onClick={() => editContent(item)}>Edit</button></td></tr>)}</tbody></table></div>}
      </section>
      <footer><span>MEDIA OS <b>•</b> USER-DEFINED CONFIGURATION</span><span>NO GENERATION OR PUBLISHING ACTIONS</span></footer>
    </main>
  );
}

function Field({ label, value, onChange, required = false, placeholder = '' }: { label: string; value: string; onChange: (value: string) => void; required?: boolean; placeholder?: string }) {
  return <label className="field"><span>{label}</span><input value={value} onChange={(event) => onChange(event.target.value)} required={required} placeholder={placeholder} /></label>;
}

function SelectField({ label, value, onChange, options }: { label: string; value: string; onChange: (value: string) => void; options: [string, string][] }) {
  return <label className="field"><span>{label}</span><select value={value} onChange={(event) => onChange(event.target.value)}><option value="">None</option>{options.map(([id, name]) => <option key={id} value={id}>{name}</option>)}</select></label>;
}
