-- Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

-- | Constraint generator for DAML LF static verification
module DA.Daml.LF.Verify.Generate
  ( genPackages
  , Phase(..)
  ) where

import Control.Monad.Error.Class (throwError)
import qualified Data.NameMap as NM

import DA.Daml.LF.Ast hiding (lookupChoice)
import DA.Daml.LF.Verify.Context
import DA.Daml.LF.Verify.Subst

data Phase
  = ValuePhase
  | TemplatePhase

data GenOutput = GenOutput
  { _goExp :: Expr
    -- ^ The expression, evaluated as far as possible.
  , _goUpd :: UpdateSet
    -- ^ The updates, performed by this expression.
  }

emptyGO :: Expr -> GenOutput
emptyGO expr = GenOutput expr emptyUpdateSet

-- | Extend a generator output with the updates of the second generator output.
-- Note that the end result will contain the first expression.
combineGO :: GenOutput -> GenOutput -> GenOutput
combineGO genOut1 genOut2
  = extendGOUpds (_goUpd genOut2)
    genOut1

updateGOExpr :: Expr
  -- ^ The new output expression.
  -> GenOutput
  -- ^ The current generator output.
  -> GenOutput
updateGOExpr expr gout = gout{_goExp = expr}

extendGOUpds :: UpdateSet
  -- ^ The extension of the update set.
  -> GenOutput
  -- ^ The current generator output.
  -> GenOutput
extendGOUpds upds gout@GenOutput{..} = gout{_goUpd = concatUpdateSet upds _goUpd}

addArchiveUpd :: Qualified TypeConName -> [(FieldName, Expr)] -> GenOutput -> GenOutput
addArchiveUpd temp fs (GenOutput expr upd@UpdateSet{..}) =
  GenOutput expr upd{_usArc = (UpdArchive temp fs) : _usArc}

genPackages :: MonadEnv m => Phase -> [(PackageId, (Package, Maybe PackageName))] -> m ()
genPackages ph inp = mapM_ (genPackage ph) inp

genPackage :: MonadEnv m => Phase -> (PackageId, (Package, Maybe PackageName)) -> m ()
genPackage ph (id, (pac, _)) = mapM_ (genModule ph (PRImport id)) (NM.toList $ packageModules pac)

-- TODO: Type synonyms and data types are ignored for now.
genModule :: MonadEnv m => Phase -> PackageRef -> Module -> m ()
genModule ValuePhase pac mod =
  mapM_ (genValue pac (moduleName mod)) (NM.toList $ moduleValues mod)
genModule TemplatePhase pac mod =
  mapM_ (genTemplate pac (moduleName mod)) (NM.toList $ moduleTemplates mod)

genValue :: MonadEnv m => PackageRef -> ModuleName -> DefValue -> m ()
genValue pac mod val = do
  expOut <- genExpr ValuePhase (dvalBody val)
  let qname = Qualified pac mod (fst $ dvalBinder val)
  extValEnv qname (_goExp expOut) (_goUpd expOut)

-- TODO: Handle annotated choices, by returning a set of annotations.
genChoice :: MonadEnv m => Qualified TypeConName -> TemplateChoice
  -> m GenOutput
genChoice tem cho = do
  -- TODO: Skolemise chcSelfBinder
  extVarEnv (fst $ chcArgBinder cho)
  expOut <- genExpr TemplatePhase (chcUpdate cho)
  if chcConsuming cho
    -- TODO: Convert the `ExprVarName`s to `FieldName`s
    then return $ addArchiveUpd tem [] expOut
    else return expOut

genTemplate :: MonadEnv m => PackageRef -> ModuleName -> Template -> m ()
-- TODO: lookup the data type and skolemise all fieldnames
-- TODO: Take precondition into account?
genTemplate pac mod Template{..} = do
  let name = Qualified pac mod tplTypeCon
  choOuts <- mapM (genChoice name) (NM.toList tplChoices)
  mapM_ (\(ch, upd) -> extChEnv name ch upd)
    $ zip (map chcName $ NM.toList tplChoices) (map _goUpd choOuts)

genExpr :: MonadEnv m => Phase -> Expr -> m GenOutput
genExpr ph = \case
  ETmApp fun arg -> genForTmApp ph fun arg
  ETyApp expr typ -> genForTyApp ph expr typ
  EVar name -> genForVar ph name
  EVal w -> genForVal ph w
  EUpdate (UCreate tem arg) -> genForCreate ph tem arg
  EUpdate (UExercise tem ch cid par arg) -> genForExercise ph tem ch cid par arg
  -- TODO: Extend additional cases
  e -> return $ emptyGO e

genForTmApp :: MonadEnv m => Phase -> Expr -> Expr -> m GenOutput
genForTmApp ph fun arg = do
  funOut <- genExpr ph fun
  argOut <- genExpr ph arg
  case _goExp funOut of
    ETmLam bndr body -> do
      let subst = singleExprSubst (fst bndr) (_goExp argOut)
          resExpr = substituteTmTm subst body
      resOut <- genExpr ph resExpr
      return $ combineGO resOut
        $ combineGO funOut argOut
    fun' -> return $ updateGOExpr (ETmApp fun' (_goExp argOut))
      $ combineGO funOut argOut

genForTyApp :: MonadEnv m => Phase -> Expr -> Type -> m GenOutput
genForTyApp ph expr typ = do
  exprOut <- genExpr ph expr
  case _goExp exprOut of
    ETyLam bndr body -> do
      let subst = singleTypeSubst (fst bndr) typ
          resExpr = substituteTyTm subst body
      resOut <- genExpr ph resExpr
      return $ combineGO resOut exprOut
    expr' -> return $ updateGOExpr (ETyApp expr' typ) exprOut

genForVar :: MonadEnv m => Phase -> ExprVarName -> m GenOutput
genForVar _ph name = lookupVar name >> return (emptyGO (EVar name))

genForVal :: MonadEnv m => Phase -> Qualified ExprValName -> m GenOutput
genForVal ValuePhase w
  = return $ GenOutput (EVal w) (emptyUpdateSet{_usVal = [w]})
genForVal TemplatePhase w
  = lookupVal w >>= \ (expr, upds) -> return (GenOutput expr upds)

genForCreate :: MonadEnv m => Phase -> Qualified TypeConName -> Expr -> m GenOutput
genForCreate ph tem arg = do
  argOut <- genExpr ph arg
  case _goExp argOut of
    argExpr@(ERecCon _ fs) ->
      return (GenOutput (EUpdate (UCreate tem argExpr)) emptyUpdateSet{_usCre = [UpdCreate tem fs]})
      -- TODO: We could potentially filter here to only store the interesting fields?
    _ -> throwError ExpectRecord

genForExercise :: MonadEnv m => Phase -> Qualified TypeConName -> ChoiceName
  -> Expr -> Maybe Expr -> Expr -> m GenOutput
genForExercise ph tem ch cid par arg = do
  cidOut <- genExpr ph cid
  argOut <- genExpr ph arg
  updSet <- lookupChoice tem ch
  return (GenOutput (EUpdate (UExercise tem ch (_goExp cidOut) par (_goExp argOut))) updSet)

