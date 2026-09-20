-- One schema for both MariaDB and SQLite. The tokens in braces are substituted by Dialect.
-- Dates are epoch milliseconds in {BIGINT}: no date type, no time zone.
-- ONLY completed trades reach the database, so escrow.trade_id has no constraint:
-- an escrow row can exist for a trade that will never make it into the trade table.

CREATE TABLE IF NOT EXISTS schema_meta (
    meta_key    {SHORT} NOT NULL,
    meta_value  {SHORT} NOT NULL,
    PRIMARY KEY (meta_key)
){OPTS};

CREATE TABLE IF NOT EXISTS player (
    uuid          {UUID} NOT NULL,
    current_name  {NAME} NOT NULL,
    first_seen    {BIGINT} NOT NULL,
    PRIMARY KEY (uuid)
){OPTS};

CREATE TABLE IF NOT EXISTS player_name_history (
    id           {ULID} NOT NULL,
    player_uuid  {UUID} NOT NULL,
    name         {NAME} NOT NULL,
    seen_at      {BIGINT} NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (player_uuid, name),
    FOREIGN KEY (player_uuid) REFERENCES player (uuid)
){OPTS};

CREATE TABLE IF NOT EXISTS species (
    identifier  {IDENT} NOT NULL,
    first_seen  {BIGINT} NOT NULL,
    PRIMARY KEY (identifier)
){OPTS};

CREATE TABLE IF NOT EXISTS item (
    identifier  {IDENT} NOT NULL,
    first_seen  {BIGINT} NOT NULL,
    PRIMARY KEY (identifier)
){OPTS};

CREATE TABLE IF NOT EXISTS trade (
    id            {ULID} NOT NULL,
    started_at    {BIGINT} NOT NULL,
    completed_at  {BIGINT} NOT NULL,
    world         {IDENT} NOT NULL,
    origin        {SHORT} NOT NULL,
    PRIMARY KEY (id)
){OPTS};

CREATE TABLE IF NOT EXISTS trade_participant (
    trade_id     {ULID} NOT NULL,
    player_uuid  {UUID} NOT NULL,
    side         {INT} NOT NULL,
    PRIMARY KEY (trade_id, player_uuid),
    FOREIGN KEY (trade_id) REFERENCES trade (id),
    FOREIGN KEY (player_uuid) REFERENCES player (uuid)
){OPTS};

CREATE TABLE IF NOT EXISTS trade_item (
    id           {ULID} NOT NULL,
    trade_id     {ULID} NOT NULL,
    from_player  {UUID} NOT NULL,
    item_id      {IDENT} NOT NULL,
    amount       {INT} NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY (trade_id, from_player) REFERENCES trade_participant (trade_id, player_uuid),
    FOREIGN KEY (item_id) REFERENCES item (identifier)
){OPTS};

CREATE TABLE IF NOT EXISTS trade_item_components (
    trade_item_id  {ULID} NOT NULL,
    payload        {BLOB} NOT NULL,
    PRIMARY KEY (trade_item_id),
    FOREIGN KEY (trade_item_id) REFERENCES trade_item (id)
){OPTS};

CREATE TABLE IF NOT EXISTS trade_pokemon (
    id                 {ULID} NOT NULL,
    trade_id           {ULID} NOT NULL,
    from_player        {UUID} NOT NULL,
    species_id         {IDENT} NOT NULL,
    form               {SHORT},
    level              {INT} NOT NULL,
    shiny              {BOOL} NOT NULL,
    gender             {SHORT},
    -- The nickname has no server-side limit and can carry formatting: with
    -- STRICT_TRANS_TABLES a long value is not truncated, it fails the INSERT.
    nickname           {LONGTEXT},
    nature             {IDENT},
    ability            {IDENT},
    held_item_id       {IDENT},
    ball               {IDENT},
    -- In Cobblemon the original trainer can be a name, not a UUID: CHAR(36) would pad
    -- it with spaces.
    original_trainer   {SHORT},
    pokemon_uuid       {UUID} NOT NULL,
    tradeable          {BOOL} NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY (trade_id, from_player) REFERENCES trade_participant (trade_id, player_uuid),
    FOREIGN KEY (species_id) REFERENCES species (identifier),
    FOREIGN KEY (held_item_id) REFERENCES item (identifier)
){OPTS};

CREATE TABLE IF NOT EXISTS trade_pokemon_stat (
    trade_pokemon_id  {ULID} NOT NULL,
    stat              {SHORT} NOT NULL,
    iv                {INT} NOT NULL,
    ev                {INT} NOT NULL,
    PRIMARY KEY (trade_pokemon_id, stat),
    FOREIGN KEY (trade_pokemon_id) REFERENCES trade_pokemon (id)
){OPTS};

CREATE TABLE IF NOT EXISTS trade_pokemon_move (
    trade_pokemon_id  {ULID} NOT NULL,
    slot              {INT} NOT NULL,
    move_id           {IDENT} NOT NULL,
    PRIMARY KEY (trade_pokemon_id, slot),
    FOREIGN KEY (trade_pokemon_id) REFERENCES trade_pokemon (id)
){OPTS};

CREATE TABLE IF NOT EXISTS trade_pokemon_aspect (
    trade_pokemon_id  {ULID} NOT NULL,
    aspect            {IDENT} NOT NULL,
    PRIMARY KEY (trade_pokemon_id, aspect),
    FOREIGN KEY (trade_pokemon_id) REFERENCES trade_pokemon (id)
){OPTS};

CREATE TABLE IF NOT EXISTS trade_pokemon_snapshot (
    trade_pokemon_id  {ULID} NOT NULL,
    format_version    {SHORT} NOT NULL,
    payload           {BLOB} NOT NULL,
    PRIMARY KEY (trade_pokemon_id),
    FOREIGN KEY (trade_pokemon_id) REFERENCES trade_pokemon (id)
){OPTS};

-- amount is what the player had OFFERED, transferred is what really entered or left their
-- balance: only the difference between the two offers is moved, in a single transaction, so
-- the two numbers almost never match.
CREATE TABLE IF NOT EXISTS trade_money (
    trade_id     {ULID} NOT NULL,
    player_uuid  {UUID} NOT NULL,
    amount       {BIGINT} NOT NULL,
    transferred  {BIGINT} NOT NULL,
    currency     {IDENT} NOT NULL,
    PRIMARY KEY (trade_id, player_uuid),
    FOREIGN KEY (trade_id, player_uuid) REFERENCES trade_participant (trade_id, player_uuid)
){OPTS};

CREATE TABLE IF NOT EXISTS escrow (
    id           {ULID} NOT NULL,
    player_uuid  {UUID} NOT NULL,
    trade_id     {ULID},
    item_id      {IDENT} NOT NULL,
    amount       {INT} NOT NULL,
    components   {BLOB},
    state        {SHORT} NOT NULL,
    created_at   {BIGINT} NOT NULL,
    resolved_at  {BIGINT},
    PRIMARY KEY (id),
    FOREIGN KEY (player_uuid) REFERENCES player (uuid),
    FOREIGN KEY (item_id) REFERENCES item (identifier)
){OPTS};

CREATE TABLE IF NOT EXISTS blacklist_item (
    id          {ULID} NOT NULL,
    item_id     {IDENT} NOT NULL,
    reason      {LONGTEXT},
    added_by    {SHORT} NOT NULL,
    created_at  {BIGINT} NOT NULL,
    revoked_at  {BIGINT},
    PRIMARY KEY (id),
    FOREIGN KEY (item_id) REFERENCES item (identifier)
){OPTS};

CREATE TABLE IF NOT EXISTS blacklist_pokemon (
    id          {ULID} NOT NULL,
    species_id  {IDENT} NOT NULL,
    form        {SHORT},
    reason      {LONGTEXT},
    added_by    {SHORT} NOT NULL,
    created_at  {BIGINT} NOT NULL,
    revoked_at  {BIGINT},
    PRIMARY KEY (id),
    FOREIGN KEY (species_id) REFERENCES species (identifier)
){OPTS};

CREATE TABLE IF NOT EXISTS blacklist_pokemon_aspect (
    blacklist_id  {ULID} NOT NULL,
    aspect        {IDENT} NOT NULL,
    PRIMARY KEY (blacklist_id, aspect),
    FOREIGN KEY (blacklist_id) REFERENCES blacklist_pokemon (id)
){OPTS};

CREATE TABLE IF NOT EXISTS api_client (
    modid       {SHORT} NOT NULL,
    key_hash    {HASH} NOT NULL,
    level       {INT} NOT NULL,
    created_at  {BIGINT} NOT NULL,
    revoked_at  {BIGINT},
    PRIMARY KEY (modid)
){OPTS};

-- Filled only while writing to the SQLite fallback: it says what to hand back to MariaDB
-- when it returns. On MariaDB it stays empty.
CREATE TABLE IF NOT EXISTS replay_pending (
    kind        {SHORT} NOT NULL,
    entity_id   {ULID} NOT NULL,
    created_at  {BIGINT} NOT NULL,
    PRIMARY KEY (kind, entity_id)
){OPTS};

CREATE INDEX IF NOT EXISTS idx_participant_player ON trade_participant (player_uuid);
CREATE INDEX IF NOT EXISTS idx_trade_completed ON trade (completed_at);
CREATE INDEX IF NOT EXISTS idx_trade_item_trade ON trade_item (trade_id);
CREATE INDEX IF NOT EXISTS idx_trade_item_item ON trade_item (item_id);
CREATE INDEX IF NOT EXISTS idx_trade_pokemon_trade ON trade_pokemon (trade_id);
CREATE INDEX IF NOT EXISTS idx_trade_pokemon_species ON trade_pokemon (species_id);
CREATE INDEX IF NOT EXISTS idx_escrow_open ON escrow (player_uuid, state);
CREATE INDEX IF NOT EXISTS idx_blacklist_item_active ON blacklist_item (item_id, revoked_at);
CREATE INDEX IF NOT EXISTS idx_blacklist_pokemon_active ON blacklist_pokemon (species_id, revoked_at);
